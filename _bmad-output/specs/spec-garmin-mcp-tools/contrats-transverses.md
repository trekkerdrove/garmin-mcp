# Contrats transverses des outils Garmin

## Accès et données

- S’appuyer sur AD-1 à AD-5 de l’[architecture](../../planning-artifacts/architecture/architecture-garmin-mcp-2026-11-21/ARCHITECTURE-SPINE.md). Conserver le serveur unique, `/health` sans appel externe et l’authentification OAuth du propriétaire sur l’ensemble de `/mcp`, y compris `tools/list`.
- Exposer des entrées typées et des sorties JSON projetées. Chaque nouvel outil déclare `readOnlyHint=true` et `destructiveHint=false`. Aucun argument n’accepte d’URL Garmin, de chemin arbitraire ou de jeton. L’adaptateur Garmin choisit les opérations et construit leurs paramètres.
- Réutiliser la construction HTTP et l’échange de jeton de `GarminHttpClient`. Les adaptateurs MCP appellent les cas d’usage existants ou nouveaux, les cas d’usage lisent par leurs ports, et l’adaptateur externe mappe les schémas Garmin. Garder les calculs testables sans serveur Spring. Le contrat de `garmin_check_connection` reste identique.
- Sélectionner les champs nécessaires à la question. Séries détaillées et champs sensibles ne sont inclus que dans les vues qui les demandent. Aucune clé, aucun jeton, aucun corps HTTP brut ni donnée personnelle dans les journaux ou artefacts de test. Utiliser des données fictives ou anonymisées pour les vérifications reproductibles.

## Dates, pagination et unités

- Les fenêtres utilisent des dates civiles inclusives au format ISO. Préserver la date locale d’origine d’une activité ou d’une mesure. Les outils indiquent leur période, leur source et les unités. Un jour en cours est signalé comme incomplet.
- `running_distance` utilise par défaut le lundi au dimanche de la semaine civile précédente dans `Europe/Paris`. Compter la famille course, y compris les segments de course éligibles. Inclure les sous-activités avec `excludeChildren=false`, dédupliquer les IDs et compter une distance une seule fois entre activité parent et segments. Un total nul exige une lecture complète.
- `compare_sleep_by_training_day` prend par défaut les 28 derniers jours civils terminés. Affecter à chaque nuit la date qui précède le réveil, y compris pour le dernier jour de la plage. Une journée comprenant au moins une séance Garmin, marche incluse, est classée entraînement. Les pas seuls ne suffisent pas. Calculer les moyennes sur les nuits mesurées, hors siestes, et l’écart entraînement moins repos lorsque les deux moyennes existent. Une panne de la lecture des activités ou du sommeil rend la comparaison indisponible.
- Respecter les fenêtres documentées par requête. Pas et stress 28 jours, sommeil, hydratation et Body Battery 31 jours, résumés v2 un an, toutes les pesées 364 jours. Découper les lectures lorsque nécessaire et prévoir un budget d’appels borné par outil. Les agrégats hebdomadaires précisent l’ancre de semaine, les jours couverts et la méthode.
- Valider les dates, IDs positifs, filtres, modes, limites et bornes avant le premier appel. Les plafonds locaux de pages, dates et échantillons sont explicites dans les schémas des outils et vérifiés par des tests. La limite locale d’une page de recherche relève du serveur. Garmin n’en documente que la valeur par défaut.
- Un résultat volumineux possède une limite de sortie et un indicateur de troncature ou de continuation. Pour une lecture exhaustive, signaler si le budget ou une panne a interrompu la collecte. Un total présenté comme complet exige la lecture de toutes les pages et fenêtres.
- Le poids individuel documenté est en kg. Les moyennes dont l’unité reste incertaine sont omises ou marquées comme telles jusqu’à vérification. Pour le sommeil, la FC de repos et la VFC, documenter la date de chaque source et éviter de joindre des dates incompatibles. Les métriques dérivées indiquent la méthode et les mesures exclues.

## Absence, erreurs et limites FIT

- Les sorties distinguent les données présentes, l’absence confirmée, la lecture tronquée et l’échec. Un zéro mesuré est une donnée valide. Un `204` documenté ou une liste vide réussie peut confirmer l’absence. Pour une lecture composite, si une source facultative échoue, conserver les résultats disponibles en signalant l’enrichissement indisponible.
- Distinguer une validation locale, un refus Garmin, une ressource absente, une limite de débit et une indisponibilité amont. Arrêter la collecte sur limitation ou expiration de son budget, sans relance incontrôlée ni renvoi du message brut de Garmin.
- Pour le lot FIT, borner téléchargement, taille décompressée, nombre d’entrées, durée, sortie et concurrence. Lire l’archive en interne et refuser les fichiers invalides ou au-delà des limites. Une archive sans membre FIT et un FIT valide sans capteur demandé ont des états distincts. Aucune écriture de fichier, URL de téléchargement, archive brute ou export au client.
- Sur Render Free, valider le dimensionnement du lot FIT en tenant compte des 512 Mo et d’appels simultanés. Les deux premiers lots n’ont pas besoin du décodeur.

## Vérification de livraison

- À chaque lot, appeler `tools/list` avec l’identité autorisée et comparer l’ensemble des noms, sans doublon, à la tranche du catalogue. Totaux attendus 13, 34, puis 36. Vérifier le plafond de 200 et le maintien de la sonde.
- Exécuter `tools/call` via l’entrée MCP normale sur des réponses Garmin simulées. Couvrir les routes des outils livrés et les cas qui changent leur résultat, dont ID d’activité inconnu, page pleine, doublon multisport, frontière de date locale, jour vide, délai ou limitation amont et période interrompue.
- Vérifier les projections d’unités, les calculs documentés et la différence entre zéro et absence. Pour le lot FIT, couvrir un enregistrement multisport, un capteur absent, une archive sans FIT, une archive illisible ou trop volumineuse et la réduction des échantillons.
- Garder les contrôles d’accès de la sonde. Une requête anonyme, émise pour un autre sujet ou pour une mauvaise audience, n’atteint pas Garmin. Les demandes d’outils invalides ne provoquent aucun appel amont.
