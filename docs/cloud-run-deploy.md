# Cloud Run: Image bauen und Service deployen

Der Service `cloud-ingestion-service` läuft als Container auf Cloud Run. Das Image liegt
auf Docker Hub.

## Überblick

| Eigenschaft | Wert |
|---|---|
| Projekt | `it-learning-hub-demo` |
| Region | `europe-west4` |
| Service | `cloud-ingestion-service` |
| Image | `docker.io/wassmd/cloud-ingestion-service:<tag>` |
| Service-Account | `ingestion-runner@it-learning-hub-demo.iam.gserviceaccount.com` |
| Zugriff | nicht öffentlich (`--no-allow-unauthenticated`) |
| Endpoint | `POST /events/gcs/file-received` |
| Max. Instanzen | 1 |
| Concurrency | 80 (Standard) |

## Variablen

```bash
PROJECT=it-learning-hub-demo
REGION=europe-west4
IMAGE=docker.io/wassmd/cloud-ingestion-service
TAG=0.0.2
RUNNER=ingestion-runner@$PROJECT.iam.gserviceaccount.com
SQL_CONN=$PROJECT:$REGION:cloudingestiondb
```

## 1. Image bauen und nach Docker Hub hochladen

```bash
docker login
docker buildx build --platform linux/amd64 -t wassmd/cloud-ingestion-service:$TAG --push .
```

Hinweise:

- Cloud Run läuft auf `linux/amd64`. Auf Apple-Silicon-Macs (`arm64`) muss deshalb
  `--platform linux/amd64` gesetzt werden.
- Im `Dockerfile` läuft die Build-Stage mit `FROM --platform=$BUILDPLATFORM`. Sie baut also
  nativ auf der Architektur des Rechners. Unter Emulation scheitert sonst das Entpacken von
  Maven mit `tar: ... Function not implemented`. Das JAR ist plattformunabhängig.
- Nur die Runtime-Stage ist `amd64`.
- Das Docker-Hub-Repository muss **öffentlich** sein, damit Cloud Run das Image direkt ziehen
  kann. Das Image enthält keine Zugangsdaten.
- Immer einen festen Tag verwenden (z. B. `0.0.2`), nicht `latest`. So lässt sich gezielt
  zurückrollen.

## 2. Anwendung: Verbindung zu Cloud SQL

- Abhängigkeit `com.google.cloud.sql:postgres-socket-factory` in der `pom.xml`. Sie verbindet
  sich über den Instanz-Verbindungsnamen statt über eine IP-Adresse.
- In `application.yaml` ist `spring.cloud.gcp.sql.enabled: false` gesetzt. Sonst aktiviert
  Spring Cloud GCP eine eigene Konfiguration, verlangt `database-name` und überschreibt die
  Datenquelle.
- Die lokale Konfiguration in `application.yaml` (`localhost:5432`) bleibt für die lokale
  Entwicklung bestehen. Auf Cloud Run überschreiben Umgebungsvariablen sie (`SPRING_DATASOURCE_*`).

## 3. APIs aktivieren

```bash
gcloud services enable run.googleapis.com sqladmin.googleapis.com secretmanager.googleapis.com \
  --project=$PROJECT
```

## 4. Service-Account anlegen und Rechte vergeben

Statt des Standard-Accounts von Compute Engine (`<Projektnummer>-compute@developer...`, der
die breite Rolle `Editor` hat) läuft der Service mit einem eigenen Account.

```bash
gcloud iam service-accounts create ingestion-runner \
  --display-name="Cloud Ingestion Service" --project=$PROJECT

# Passwort-Secret lesen (nur dieses Secret)
gcloud secrets add-iam-policy-binding cloudingestiondb \
  --member="serviceAccount:$RUNNER" \
  --role="roles/secretmanager.secretAccessor" --project=$PROJECT

# Mit Cloud SQL verbinden
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$RUNNER" --role="roles/cloudsql.client"
```

Lesezugriff auf den Bucket: siehe [bucket-setup.md](bucket-setup.md).

| Rolle | Vergeben auf | Wirkung |
|---|---|---|
| `secretmanager.secretAccessor` | Secret `cloudingestiondb` | Passwort lesen, nur dieses Secret |
| `cloudsql.client` | Projekt | Verbindung zu allen Cloud-SQL-Instanzen im Projekt |
| `storage.objectViewer` | Bucket | Dateien lesen |

## 5. Passwort als Secret ablegen

Das Passwort gehört nicht ins Image und nicht in den Code.

```bash
printf "%s" "<passwort>" | gcloud secrets create cloudingestiondb \
  --data-file=- --project=$PROJECT
```

Das Passwort muss zum Datenbank-Benutzer `postgres` passen
(siehe [cloud-sql-setup.md](cloud-sql-setup.md)).

## 6. Deployen

```bash
gcloud run deploy cloud-ingestion-service \
  --image $IMAGE:$TAG \
  --region $REGION --project $PROJECT \
  --service-account=$RUNNER \
  --no-allow-unauthenticated \
  --max-instances=1 \
  --add-cloudsql-instances=$SQL_CONN \
  --set-env-vars "SPRING_DATASOURCE_URL=jdbc:postgresql:///cloud_ingestion_db?cloudSqlInstance=$SQL_CONN&socketFactory=com.google.cloud.sql.postgres.SocketFactory,SPRING_DATASOURCE_USERNAME=postgres" \
  --set-secrets SPRING_DATASOURCE_PASSWORD=cloudingestiondb:latest
```

Die Cloud-SQL-Instanz muss laufen, sonst startet die neue Revision nicht (Flyway migriert
beim Start).

Nur den Service-Account auf einem bestehenden Service ändern:

```bash
gcloud run services update cloud-ingestion-service \
  --region $REGION --project $PROJECT --service-account=$RUNNER
```

`update` legt eine neue Revision an, ohne ein neues Image zu verwenden. Die neue Revision
bekommt den Datenverkehr erst, wenn sie erfolgreich gestartet ist. Die alte läuft sonst weiter.

## 7. Prüfen

Konfiguration ansehen:

```bash
gcloud run services describe cloud-ingestion-service --region $REGION --project $PROJECT
```

Service-Account abfragen:

```bash
gcloud run services describe cloud-ingestion-service --region $REGION --project $PROJECT \
  --format='value(spec.template.spec.serviceAccountName)'
```

Logs:

```bash
gcloud run services logs read cloud-ingestion-service \
  --region $REGION --project $PROJECT --limit 30
```

Aufruf ohne Token muss `403` liefern. Mit Identitätstoken:

```bash
URL=$(gcloud run services describe cloud-ingestion-service --region $REGION --project $PROJECT \
  --format='value(status.url)')

curl -i -X POST \
  -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  -H "Content-Type: application/json" \
  -d '{"bucket":"test","name":"readme.txt"}' \
  $URL/events/gcs/file-received
```

Erwartet: `204` (Datei wird übersprungen, weil sie nicht auf `.jsonl` endet).
Das Identitätstoken ist etwa eine Stunde gültig und wie ein Passwort zu behandeln.

## Einstellungen und ihre Bedeutung

| Einstellung | Bedeutung |
|---|---|
| `--no-allow-unauthenticated` | Nur Konten mit `roles/run.invoker` dürfen aufrufen (später der Eventarc-Trigger). |
| `--max-instances=1` | Höchstens eine Instanz läuft gleichzeitig. Begrenzt Kosten und parallele Importe. |
| Concurrency 80 | Eine Instanz bearbeitet bis zu 80 Anfragen gleichzeitig. Bei großen Importen und 512 MiB Speicher kann ein kleinerer Wert (`--concurrency=4`) sinnvoll sein. |
| Min. Instanzen 0 | Ohne Aufrufe fährt der Service auf null herunter. Der erste Aufruf braucht danach einige Sekunden (Kaltstart). |

## Fehlersuche

| Meldung / Beobachtung | Ursache |
|---|---|
| `Permission denied on secret` beim Start | `secretAccessor` fehlt für den Service-Account. |
| Fehler zur Cloud SQL Admin API | `cloudsql.client` fehlt, oder `sqladmin.googleapis.com` ist nicht aktiviert. |
| `password authentication failed for user` | Passwort im Secret passt nicht zum Benutzer. |
| `Connection to localhost:5432 refused` | `SPRING_DATASOURCE_URL` ist nicht gesetzt oder falsch geschrieben. |
| `ClassNotFoundException ... SocketFactory` | Die Abhängigkeit `postgres-socket-factory` fehlt im Image. |
| Image kann nicht gezogen werden | Docker-Hub-Repository ist nicht öffentlich, oder das Image wurde für `arm64` gebaut. |
| Revision startet nicht | Cloud-SQL-Instanz ist gestoppt. Logs prüfen. |
