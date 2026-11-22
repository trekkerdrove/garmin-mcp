---
title: 'Création de séances de musculation Garmin'
type: 'feature'
created: '2026-11-22'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="intention utilisateur">

## Intent

Implémenter les trois capacités, les bornes et les critères de vérification du
[SPEC](../specs/spec-garmin-strength-workouts/SPEC.md) et de son
[contrat](../specs/spec-garmin-strength-workouts/contrat-musculation.md),
puis publier sur main et vérifier le déploiement automatique.

</frozen-after-approval>

## Implementation Notes

- Travail isolé du répertoire principal. Réutilisation de la session et de la
  vérification de compte existantes pour la création.
- Les blocs sont encodés en étapes exécutables. La comparaison développe aussi
  les groupes Garmin à nombre fixe et respecte la suppression du dernier repos.
- Garmin Connect a relu 62,5 kg en kilogrammes, un gainage chronométré et des
  repos de 90, 45, 120 et 60 secondes. Le même appel a réutilisé un seul ID.
  Une transition différente a déclenché un conflit. Les séances de test ont été supprimées.

## Review Triage Log

- Medium, corrigé. Les groupes correspondants sont développés dans l’ordre avec
  leurs répétitions et le dernier repos facultatif, y compris recovery temporisé.
- Medium, corrigé. Le catalogue contient des homonymes. La recherche accepte une
  paire catégorie/clé et les libellés distinguent les catégories.
- Medium, corrigé. La relecture vérifie l’ID retourné avant de confirmer l’écriture.
- Low, corrigé. L’aperçu indique explicitement une charge absente par une valeur nulle.
- Medium, corrigé. Deux identifiants du même nom produisent un conflit pour la musculation.
- Maybe-false, différé. Aucun retard de visibilité n’a été observé sur les écritures
  et rejeux réels. Reproduire une création invisible dans la liste après un échec
  de relecture pour déterminer si un suivi des écritures en attente est nécessaire.
- Low, corrigé. Le cache expire après un jour et rejette un catalogue incomplet.
- Medium, corrigé. Un test du handler MCP couvre les blocs typés, l’aperçu et le
  retour de l’ID après vérification du compte. L’essai réel a couvert le bridge.
- Low, corrigé. Le README indique la portée du catalogue Personal API et identifie
  la source publique des exercices.
