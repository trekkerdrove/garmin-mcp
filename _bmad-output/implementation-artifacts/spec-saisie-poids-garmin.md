---
title: 'Saisie et correction du poids Garmin par date'
type: 'feature'
created: '2026-11-21'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="intention utilisateur">

## Intent

Ajouter un outil MCP unique pour saisir ou corriger une pesée en kg à une date donnée, avec aujourd’hui par défaut dans Europe/Paris. L’aperçu indique la date, la valeur et l’éventuelle mesure à corriger. Une correction confirmée vise un seul identifiant. Si la journée contient plusieurs mesures, l’outil présente les heures et les identifiants pour permettre un choix explicite.

Réutiliser la session Garmin Connect déjà configurée et conserver la lecture via la Personal API. Pour corriger une pesée manuelle sans composition corporelle, écrire à la même heure et vérifier le résultat avant de supprimer l’ancien enregistrement si Garmin l’a conservé. Si la correction s’interrompt, la tentative suivante reconnaît la mesure vérifiée et ne crée pas de doublon. Les tests qui écrivent utilisent exclusivement des pesées temporaires isolées.

</frozen-after-approval>

## Implementation Notes

- `garmin_set_weight` demande la valeur `selection` de la mesure choisie lorsque l’appel confirmé vise un jour déjà renseigné. Elle contient son identifiant, ses horodatages et son poids initial sous forme de chaîne. La création accepte une heure locale facultative et utilise minuit par défaut.
- Le service et le processus Python partagent le même accès vérifié et le même verrou que les outils d’entraînement. La correction conserve l’heure Garmin d’origine en dérivant son décalage à partir des deux timestamps de la mesure. Aucun code ne déduit l’identifiant à supprimer de la valeur du poids.
- Le contrôle de structure sur le compte a confirmé les deux timestamps en millisecondes et le poids en grammes. Les tests Python couvrent la sélection, la protection de la composition, le rejeu, la panne après écriture et la reprise après une suppression incertaine.
- Garmin calcule un IMC sur une nouvelle pesée manuelle. L’IMC calculé est compatible avec la correction. La comparaison après écriture retient l’identité, la valeur et l’heure exacte de la mesure, indépendamment des champs recalculés.
- Le test HTTP MCP sur le compte a ajouté une mesure fictive sur un jour vérifié vide, rejoué la saisie, corrigé la mesure en gardant son heure et vérifié la valeur unique via la Personal API. Le nettoyage a remis la journée dans son état initial.

## Review Triage Log

- **Moyen, corrigé.** La sélection approuvée contient les horodatages de la pesée d’origine. Si celle-ci est déjà absente au rejeu, le service peut vérifier la présence d’un remplacement exact sans écrire.
- **Moyen, atténué.** Les relectures bornées absorbent un délai de cohérence ordinaire. Une écriture toujours indéterminée exige de relire la journée avant toute nouvelle confirmation. Une garantie durable d’idempotence dépend d’une clé prise en charge par Garmin ou d’un journal persistant, et reste différée.
- **Élevé, corrigé.** L’égalité inclut l’heure civile et le décalage. Un test distingue deux mesures au même instant qui ont des heures civiles différentes.
- **Élevé, corrigé.** La valeur `selection` lie la confirmation aux horodatages et au poids de la mesure d’origine. Le serveur refuse une mesure modifiée et l’outil demande un nouvel aperçu.
- **Moyen, corrigé.** Les refus explicites de Garmin gardent leur statut. Le test vérifie qu’un refus d’authentification ne déclenche pas la suppression.
- **Moyen, corrigé.** Un test hors ligne exécute le fournisseur d’outils Spring AI et vérifie le schéma et la conservation d’un identifiant au-delà de la précision JSON des nombres JavaScript.
