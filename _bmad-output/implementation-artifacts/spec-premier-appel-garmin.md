---
title: Premier appel Garmin depuis le serveur MCP
type: feature
created: '2026-11-21'
status: done
route: oneshot
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-garmin-mcp-2026-11-21/ARCHITECTURE-SPINE.md
---

<frozen-after-approval reason="intention de la demande">

## Intent

Créer une première version locale du serveur Spring MCP. Un appel de test en lecture seule confirme que le propriétaire authentifié peut atteindre Garmin. Fournir l’image Docker à construire pour Render et vérifier le trajet MCP avec des clés de test. Le déploiement est exclu de cette livraison.

</frozen-after-approval>

## Implementation Notes

- `garmin_check_connection` vérifie la connexion en demandant une seule activité et ne retourne que le statut et l’existence éventuelle d’une activité.
- Le point d’accès MCP sans session est protégé par JWT OAuth. Spring Security publie lui-même les métadonnées avec l’audience configurée et l’émetteur OAuth.
- Le fichier `.env` est chargé localement au format properties et exclu de Git et du contexte Docker. Le Dockerfile compile et teste avec Java 21, puis lance le JAR sous un utilisateur non privilégié.
- Le projet a nécessité l’initialisation locale des scripts BMad avant le lancement de Build. L’arborescence d’architecture préexistante a servi de référence.
- `mvn verify` valide les refus OAuth et l’appel MCP autorisé sur API simulée. La construction Docker locale passe. Un test local du client sur l’API officielle, avec le secret dans `.env`, renvoie `connected`.
- Version revue vérifiée avec Maven et dans le build Docker Java 21 sur Linux amd64. Le conteneur tourne avec 512 Mo et expose une sonde saine avec MCP fermé tant que l’authentification est absente. La lecture Garmin réelle renvoie `connected`. La connexion depuis ChatGPT web reste à tester avec l’émetteur OAuth et Render.

## Review Triage Log

- **High · corrigé** — Le client rejette une clé contenant des caractères invalides avant d’ouvrir une requête. Le test vérifie que le résultat se limite au statut d’erreur.
- **Medium · corrigé** — L’autorisation et la vérification d’Origin utilisent le même sélecteur de route.
- **Medium · corrigé** — Le point d’entrée OAuth de Spring Security annonce les métadonnées et distingue un jeton expiré par `invalid_token`.
- **Medium · corrigé** — Le validateur accepte aussi le type de jeton `at+jwt`, couvert par le test d’intégration.
- **Low · corrigé** — Git exclut maintenant les variantes `.env.*` comme Docker.
- **Medium · corrigé** — Le README documente un appel local authentifié et l’injection d’un `.env` dans le conteneur.
- **Medium · corrigé** — Les tests comparent le payload complet pour une activité ou une liste vide, et vérifient que le client réutilise le jeton Garmin.
