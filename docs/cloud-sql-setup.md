# Cloud SQL (PostgreSQL)

Die Anwendung speichert importierte Daten in einer PostgreSQL-Datenbank auf Cloud SQL.

## Überblick

| Eigenschaft | Wert |
|---|---|
| Projekt | `it-learning-hub-demo` |
| Instanz | `cloudingestiondb` |
| Verbindungsname | `it-learning-hub-demo:europe-west4:cloudingestiondb` |
| Region | `europe-west4` |
| Edition | Enterprise (nicht Enterprise Plus) |
| Version | PostgreSQL 18 |
| Datenbank | `cloud_ingestion_db` |
| Benutzer | `postgres` |
| Passwort | im Secret Manager, Secret `cloudingestiondb` |
| Verbindung | Public IP aktiviert, Private IP deaktiviert |

`cloudingestiondb` ist der Name der **Instanz** (des Servers). Die **Datenbank** darin heißt
`cloud_ingestion_db`. Das Secret trägt zufällig denselben Namen wie die Instanz.

## Variablen

```bash
PROJECT=it-learning-hub-demo
REGION=europe-west4
INSTANCE=cloudingestiondb
```

## Kosten

Eine Cloud-SQL-Instanz wird **pro Stunde abgerechnet, solange sie läuft**, auch ohne Nutzung.
Sie ist der größte Kostenfaktor im Projekt.

- Enterprise Plus mit 8 vCPU und 64 GB RAM kostet mehrere hundert Dollar im Monat und ist für
  dieses Projekt nicht nötig. Eine kleine Instanz genügt.
- Die tatsächliche Größe steht in der Konsole unter *Cloud SQL → Instanz → Overview →
  Configuration*.
- Das Guthaben der Free Trial gilt auch für Cloud SQL. Cloud SQL gehört nicht zum dauerhaft
  kostenlosen Kontingent.

Instanz anhalten (es fallen nur noch Speicherkosten an) und wieder starten:

```bash
gcloud sql instances patch $INSTANCE --activation-policy=NEVER  --project=$PROJECT
gcloud sql instances patch $INSTANCE --activation-policy=ALWAYS --project=$PROJECT
```

Eine Instanz, die nicht mehr gebraucht wird, sollte gelöscht werden.

### Budget-Warnung einrichten

Ein Budget warnt nur, es stoppt keine Ausgaben. Kosten werden mit Verzögerung gemeldet.

*Billing → Budgets & alerts → Create budget*: Betrag z. B. 100 EUR, Schwellenwerte 50 %,
90 % und 100 %, E-Mail-Benachrichtigung aktivieren.

## Instanz anlegen (Beispiel, kleine Konfiguration)

Die vorhandene Instanz wurde über die Konsole angelegt. Der folgende Befehl ist ein Beispiel für
eine kleine Instanz. Die Werte (Tier, Speicher) sind nicht gegen die vorhandene Instanz
geprüft.

```bash
gcloud sql instances create $INSTANCE \
  --database-version=POSTGRES_18 \
  --edition=ENTERPRISE \
  --tier=db-f1-micro \
  --region=$REGION \
  --availability-type=ZONAL \
  --storage-size=10GB \
  --project=$PROJECT
```

In der Konsole entspricht das: Edition **Enterprise**, Preset **Sandbox**, Region
`europe-west4`, Single zone, kleiner Speicher. Automatische Backups und Point-in-time recovery
können für ein Lernprojekt ausgeschaltet bleiben.

## Datenbank und Benutzer anlegen

```bash
gcloud sql databases create cloud_ingestion_db --instance=$INSTANCE --project=$PROJECT
```

Der Benutzer `postgres` existiert automatisch. Das Passwort kann nicht ausgelesen, nur neu
gesetzt werden:

```bash
gcloud sql users set-password postgres \
  --instance=$INSTANCE --project=$PROJECT --prompt-for-password
```

Danach im Secret Manager eine neue Version des Secrets `cloudingestiondb` mit dem gleichen
Passwort anlegen. Ohne das schlägt die Anmeldung der App fehl.

Benutzer auflisten:

```bash
gcloud sql users list --instance=$INSTANCE --project=$PROJECT
```

Ein eigener Benutzer mit weniger Rechten ist besser als `postgres` (Administratorrechte):

```bash
gcloud sql users create appuser --instance=$INSTANCE --project=$PROJECT --password=<passwort>
```

Dann beim Deploy `SPRING_DATASOURCE_USERNAME=appuser` setzen und das Secret entsprechend
anpassen.

## Tabellen

Die Tabellen werden nicht von Hand angelegt. Flyway führt beim Start der Anwendung die
Migrationen aus `src/main/resources/db/migration` aus (derzeit `V1__promotionprice.sql`).

## Verbindung von Cloud Run

Die Anwendung verbindet sich über die Cloud-SQL-Socket-Factory mit dem Verbindungsnamen:

```
jdbc:postgresql:///cloud_ingestion_db?cloudSqlInstance=it-learning-hub-demo:europe-west4:cloudingestiondb&socketFactory=com.google.cloud.sql.postgres.SocketFactory
```

Der Ablauf besteht aus zwei getrennten Prüfungen:

| Schritt | Geprüft von | Womit |
|---|---|---|
| Zugriff auf die Instanz | Google (IAM) | Service-Account mit `roles/cloudsql.client`, die API `sqladmin.googleapis.com` muss aktiv sein |
| Anmeldung an der Datenbank | PostgreSQL | Benutzername und Passwort |

Dadurch muss keine IP-Adresse freigegeben werden. Die Verbindung ist verschlüsselt. Die
Berechtigung des Service-Accounts ersetzt die IP-Freigabe. Details zu Account und Rollen:
[cloud-run-deploy.md](cloud-run-deploy.md).

## Lokal gegen die Cloud-Datenbank verbinden

Nur für Tests. Die Instanz hat eine Public IP, lässt aber nur freigegebene Adressen zu.

1. Eigene IP unter *Connections → Networking → Authorized networks* freigeben.
2. URL: `jdbc:postgresql://<ÖFFENTLICHE_IP>:5432/cloud_ingestion_db`

Die freigegebene IP danach wieder entfernen.

Im Konsolen-Dialog „Log in to your database" (Cloud SQL Studio) meldest du dich mit
Benutzer und Passwort an. Ein ungültiges Passwort blockiert die Anmeldung.

## Hinweise

- Die lokalen Tests benötigen keine Cloud-Datenbank. Sie verwenden eine eingebettete
  PostgreSQL-Datenbank (Zonky).
- Die lokale Entwicklung nutzt `compose.yaml` und `localhost:5432`.
- `postgres` ist ein Administrator-Benutzer. Für produktive Umgebungen einen eigenen
  Benutzer verwenden. IAM-Datenbank-Authentifizierung (ohne Passwort) wäre ebenfalls möglich,
  ist bei dieser Instanz aber nicht aktiviert.
