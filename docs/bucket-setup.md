# Cloud-Storage-Bucket für JSONL-Dateien

Der Bucket nimmt die JSONL-Dateien auf, die der `cloud-ingestion-service` importiert.

## Überblick

| Eigenschaft | Wert |
|---|---|
| Projekt | `it-learning-hub-demo` |
| Bucket | `it-learning-hub-demo-jsonl-eu4` |
| Region | `europe-west4` |
| Zugriffssteuerung | Uniform bucket-level access |
| Lesender Account | `ingestion-runner@it-learning-hub-demo.iam.gserviceaccount.com` |

Struktur im Bucket:

```
raw/<Jahr>/<Monat>/<Tag>/<datei>.jsonl
z. B. raw/2026/11/01/test.jsonl
```

## Warum diese Region

Die Region eines Buckets lässt sich nachträglich nicht ändern. Der erste Bucket
(`it-learning-hub-demo-jsonl`) lag in `europe-west3`, Cloud Run und Cloud SQL in `europe-west4`.
Damit alles in einer Region liegt (einfacherer Eventarc-Trigger, kein Transfer zwischen Regionen),
wurde der Bucket in `europe-west4` neu angelegt, der Inhalt kopiert und der alte Bucket gelöscht.

## Variablen

```bash
PROJECT=it-learning-hub-demo
REGION=europe-west4
BUCKET=it-learning-hub-demo-jsonl-eu4
RUNNER=ingestion-runner@$PROJECT.iam.gserviceaccount.com
```

## Bucket anlegen

Bucket-Namen sind weltweit eindeutig und lassen sich nicht umbenennen.

```bash
gcloud storage buckets create gs://$BUCKET \
  --location=$REGION \
  --uniform-bucket-level-access \
  --project=$PROJECT
```

Region prüfen:

```bash
gcloud storage buckets describe gs://$BUCKET --format='value(location)'
```

Erwartet: `EUROPE-WEST4`.

## Zugriff vergeben

Die Anwendung liest Dateien aus dem Bucket. Die Rolle wird am **Bucket** vergeben,
nicht am Projekt.

```bash
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:$RUNNER" \
  --role="roles/storage.objectViewer"
```

Prüfen:

```bash
gcloud storage buckets get-iam-policy gs://$BUCKET
```

Wer Dateien hochlädt, braucht eigene Rechte (z. B. `roles/storage.objectCreator`).
Der Projekt-Owner hat sie bereits.

## Dateien hochladen und ansehen

```bash
gcloud storage cp test.jsonl gs://$BUCKET/raw/2026/11/12/test.jsonl
gcloud storage ls -r gs://$BUCKET/
```

Ein Upload löst über Eventarc den Import aus, sobald der Trigger eingerichtet ist
(siehe [eventarc-setup.md](eventarc-setup.md)). Bereits vorhandene Dateien lösen kein
Ereignis aus.

## Inhalt in einen anderen Bucket umziehen

```bash
gcloud storage cp -r "gs://ALTER-BUCKET/*" gs://NEUER-BUCKET/
gcloud storage ls -r gs://NEUER-BUCKET/    # Kopie prüfen
```

## Bucket löschen

Das Löschen entfernt den Bucket samt Inhalt und ist nicht rückgängig zu machen.
Vorher prüfen, ob ein Eventarc-Trigger oder ein anderes Programm den Bucket verwendet.

```bash
gcloud storage rm -r gs://$BUCKET
```

## Hinweise

- Die Anwendung kennt den Bucket-Namen nicht. Er kommt bei jedem Aufruf aus dem Ereignis
  (`bucket` und `name`). Bei einem neuen Bucket ist daher keine Code-Änderung nötig.
- Die Anwendung verarbeitet jede Datei, deren Name auf `.jsonl` endet, unabhängig vom Ordner.
