- source_spec: `_bmad-output/implementation-artifacts/spec-saisie-poids-garmin.md`
  summary: Examiner l’idempotence durable de l’ajout d’une pesée si Garmin étend son API.
  evidence: Une lecture peut rester indéterminée après le délai de vérification. Vérifier si Garmin accepte une clé d’idempotence avant d’introduire un stockage persistant pour les écritures ambiguës.
