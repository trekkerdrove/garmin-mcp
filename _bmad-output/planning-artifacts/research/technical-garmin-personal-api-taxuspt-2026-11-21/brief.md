# Cartographier Taxuspt et la Garmin Connect Personal API

## Décision

Choisir les outils MCP à porter dans le serveur Java et repérer ce que la Personal API permet d’ajouter.

- Figer le code Taxuspt et compter les outils déclarés par module. Vérifier les dépendances des outils, leurs calculs et leurs formats.
- Extraire le document OpenAPI publié par Garmin, puis confirmer les routes, les paramètres et les schémas avec le SDK officiel lorsque celui-ci couvre la même opération.
- Identifier les portages directs, les résultats reconstruits côté Java, les couvertures partielles et les opérations hors du contrat. Pour chaque possibilité, citer les routes exactes.
- Dégager les fonctionnalités supplémentaires, y compris les paramètres et champs officiels absents des outils Taxuspt.

Recherche technique demandée explicitement le 21 novembre 2026. Vérification statique sur sources primaires publiques, en parallèle par familles. Aucun compte Garmin utilisé.
