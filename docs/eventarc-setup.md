# Eventarc-Setup: Automatischer Import neuer JSONL-Dateien

Diese Anleitung richtet ein, dass der Cloud-Run-Service `cloud-ingestion-service`
automatisch aufgerufen wird, sobald eine neue Datei im Cloud-Storage-Bucket landet.

## Überblick

```
Datei-Upload in den Bucket
        │
        ▼
Cloud Storage  ── Ereignis "object finalized" ──►  Pub/Sub (von Eventarc verwaltet)
                                                        │
                                                        ▼
                                    Eventarc-Trigger (läuft als ingestion-trigger)
                                                        │  HTTP POST mit Token
                                                        ▼
                         Cloud Run: POST /events/gcs/file-received
                         (läuft als ingestion-runner)
                                                        │
                           liest Datei aus dem Bucket ──┴──► schreibt in Cloud SQL
```

| Komponente | Wert |
|---|---|
| Projekt | `it-learning-hub-demo` |
| Region (Cloud Run, Cloud SQL, Bucket, Trigger) | `europe-west4` |
| Bucket | `it-learning-hub-demo-jsonl-eu4` |
| Cloud-Run-Service | `cloud-ingestion-service` |
| Endpoint | `POST /events/gcs/file-received` |
| Service-Account der App | `ingestion-runner@it-learning-hub-demo.iam.gserviceaccount.com` |
| Service-Account des Triggers | `ingestion-trigger@it-learning-hub-demo.iam.gserviceaccount.com` |

Bucket-Struktur: `raw/<Jahr>/<Monat>/<Tag>/<datei>.jsonl`

## Verhalten der Anwendung

- Der Endpoint verarbeitet nur Objekte, deren Name auf `.jsonl` endet.
  Alle anderen Dateien werden übersprungen (`204 No Content`).
- Der Ordner (`raw/`) wird **nicht** geprüft. Jede `.jsonl`-Datei im Bucket wird importiert.

## Voraussetzungen

- Eingeloggt mit `gcloud auth login` und Rolle `Owner` (oder passende IAM-Rechte) im Projekt.
- Der Cloud-Run-Service ist deployt und läuft mit dem Account `ingestion-runner`.
- Die Cloud-SQL-Instanz `cloudingestiondb` läuft (der Import schreibt dorthin).

## Variablen

```bash
PROJECT=it-learning-hub-demo
REGION=europe-west4
BUCKET=it-learning-hub-demo-jsonl-eu4
RUNNER=ingestion-runner@$PROJECT.iam.gserviceaccount.com
TRIGGER_SA=ingestion-trigger@$PROJECT.iam.gserviceaccount.com
```

## Schritte

### 0. Region des Buckets prüfen

Die Trigger-Region muss zur Bucket-Region passen.

```bash
gcloud storage buckets describe gs://$BUCKET --format='value(location)'
```

Erwartet: `EUROPE-WEST4`. Bei einer anderen Region oder einer Multi-Region (z. B. `EU`)
muss `REGION` für den Trigger entsprechend angepasst werden.

### 1. APIs aktivieren

```bash
gcloud services enable eventarc.googleapis.com pubsub.googleapis.com --project=$PROJECT
```

### 2. Lesezugriff der App auf den Bucket

Die App liest die Dateien beim Import. Die Rolle wird am **Bucket** vergeben, nicht am Projekt.

```bash
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:$RUNNER" \
  --role="roles/storage.objectViewer"
```

### 3. Eigener Service-Account für den Trigger

```bash
gcloud iam service-accounts create ingestion-trigger \
  --display-name="Eventarc trigger" --project=$PROJECT
```

Der Trigger darf den Service aufrufen:

```bash
gcloud run services add-iam-policy-binding cloud-ingestion-service \
  --region=$REGION --project=$PROJECT \
  --member="serviceAccount:$TRIGGER_SA" \
  --role="roles/run.invoker"
```

Der Trigger darf Ereignisse empfangen:

```bash
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$TRIGGER_SA" \
  --role="roles/eventarc.eventReceiver"
```

### 4. Cloud Storage darf Ereignisse in Pub/Sub veröffentlichen

Der Service-Agent von Cloud Storage braucht die Rolle `pubsub.publisher`.

```bash
GCS_SA=$(gcloud storage service-agent --project=$PROJECT)
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$GCS_SA" \
  --role="roles/pubsub.publisher"
```

### 5. Trigger anlegen

```bash
gcloud eventarc triggers create ingestion-file-finalized \
  --location=$REGION --project=$PROJECT \
  --destination-run-service=cloud-ingestion-service \
  --destination-run-region=$REGION \
  --destination-run-path=/events/gcs/file-received \
  --event-filters="type=google.cloud.storage.object.v1.finalized" \
  --event-filters="bucket=$BUCKET" \
  --service-account=$TRIGGER_SA
```

## Prüfen

Trigger anzeigen:

```bash
gcloud eventarc triggers list --location=$REGION --project=$PROJECT
```

Danach eine **neue** Datei hochladen, z. B. nach `raw/2026/11/12/test.jsonl`.
Bereits vorhandene Dateien lösen kein Ereignis aus.

Logs ansehen:

```bash
gcloud run services logs read cloud-ingestion-service \
  --region=$REGION --project=$PROJECT --limit=30
```

Erwartete Zeile:

```
Received event id=... type=google.cloud.storage.object.v1.finalized object=gs://it-learning-hub-demo-jsonl-eu4/raw/...
```

## Fehlersuche

| Beobachtung | Mögliche Ursache |
|---|---|
| Kein Log-Eintrag nach dem Upload | Trigger-Region passt nicht zur Bucket-Region (Schritt 0), oder der Trigger ist noch nicht aktiv (kann einige Minuten dauern). |
| Trigger anlegen schlägt mit Rechtefehler fehl | `pubsub.publisher` für den Cloud-Storage-Service-Agent fehlt (Schritt 4). |
| `403` im Service-Log oder in den Eventarc-Metriken | `run.invoker` fehlt für `ingestion-trigger` (Schritt 3). |
| Log zeigt `Received event`, danach Fehler beim Lesen | `storage.objectViewer` fehlt für `ingestion-runner` (Schritt 2). |
| Fehler beim Schreiben in die Datenbank | Cloud-SQL-Instanz gestoppt, oder Passwort bzw. Benutzer im Secret passt nicht. |
| `422` | Die Datei hat ein ungültiges Format (`InvalidIngestionFileException`). Details stehen im Log. |

Eventarc wiederholt fehlgeschlagene Zustellungen automatisch. Eine Datei kann daher mehrfach
verarbeitet werden. Der Import sollte idempotent sein.

## Aufräumen

```bash
gcloud eventarc triggers delete ingestion-file-finalized --location=$REGION --project=$PROJECT
```

Die Service-Accounts und Rollen bleiben bestehen. Entfernen mit
`gcloud iam service-accounts delete $TRIGGER_SA`.
