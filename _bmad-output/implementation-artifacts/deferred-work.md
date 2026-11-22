- source_spec: `_bmad-output/implementation-artifacts/spec-saisie-poids-garmin.md`
  summary: Examiner l’idempotence durable de l’ajout d’une pesée si Garmin étend son API.
  evidence: Une lecture peut rester indéterminée après le délai de vérification. Vérifier si Garmin accepte une clé d’idempotence avant d’introduire un stockage persistant pour les écritures ambiguës.

- source_spec: spec-garmin-strength-workouts.md
  summary: Vérifier un éventuel retard de visibilité d’une séance Garmin après création.
  evidence: Les créations réelles étaient visibles lors du premier rejeu. Reproduire un GET après création en échec, suivi d’une liste sans la séance créée, pour confirmer le risque de doublon.
