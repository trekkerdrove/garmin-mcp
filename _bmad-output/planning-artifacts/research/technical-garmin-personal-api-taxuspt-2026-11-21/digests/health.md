# Santé, nutrition, poids, profil

Consultation le 21 novembre 2026 (Europe/Paris). Inventaire de **62 outils** à `a2ca5fcac34524b83814fe2fcf3d479f8a75983f`, chacun enregistré par `@app.tool()` sans nom substitué. Les profils et listes d’activation filtrent l’enregistrement au démarrage. La dépendance est `garminconnect==0.3.2`. [Démarrage et filtre Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/__init__.py#L509) · [Manifest Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/pyproject.toml#L13) · [Bibliothèque source v0.3.2](https://github.com/cyberjunky/python-garminconnect/blob/0.3.2/garminconnect/__init__.py)

Garmin publie le contrat OpenAPI 3.0.1, API 1.0.0, dans le JavaScript de sa page Swagger. L’extraction du bundle consulté donne 28 chemins HTTP, tous en GET. SHA-256 du JSON extrait `ff3ffc1cd615ca594814d7fa52aa24aae4987d1b068860c3334d0d5019b005dd`. Le SDK public confirme les paramètres de plusieurs appels. Correspondances établies sur documentation et code source, sans appels authentifiés ni validation de réponses en production. **Direct** indique que l’opération et son jeu de données principal sont documentés. **Dérivé** indique une sélection, une jointure ou un calcul Java avec les données documentées. **Partiel** indique un sous-ensemble utile ou une sémantique ou un champ observable qui reste sans équivalent établi. **Absent** indique qu’aucune opération documentée ne fournit la capacité principale. Les statuts ne promettent pas une réponse JSON identique octet pour octet. [Swagger Garmin](https://developer.garmin.com/gc-developer-program/personal/swagger-docs) · [Bundle Garmin](https://developer.garmin.com/component---src-pages-gc-developer-program-personal-swagger-docs-js-7ced9e541531dfdd103f.js) · [Client Garmin](https://github.com/garmin/connect-personal-api-python/blob/958ef5883ba3b74f1b27a5c00dafc4e96351e723/src/garmin_personal_api/client.py)

## Routes officielles employées dans les tableaux

Chaque route ci-dessous est un GET. Les noms de paramètres et les bornes proviennent du Swagger. [Swagger Garmin](https://developer.garmin.com/gc-developer-program/personal/swagger-docs)

| Alias | Route et paramètres utiles |
| --- | --- |
| Résumé | `/usersummary-service/usersummary/daily?calendarDate=…` |
| Résumés v2 | `/usersummary-service/v2/dailysummaries?startDate=…&endDate=…` (1 an au maximum) |
| Pas | `/usersummary-service/stats/steps/daily/{startDate}/{endDate}` (28 jours au maximum) |
| Stress | `/usersummary-service/stats/stress/daily/{startDate}/{endDate}` (28 jours au maximum) |
| Hydratation | `/usersummary-service/stats/hydration/daily/{startDate}/{endDate}` (31 jours au maximum) |
| Batterie | `/wellness-service/wellness/bodyBattery/reports/daily?startDate=…&endDate=…` (31 jours au maximum, `chunks` facultatif) |
| FC | `/wellness-service/wellness/dailyHeartRate?date=…` |
| Sommeil | `/sleep-service/sleep/dailySleep?date=…` |
| Sommeils | `/sleep-service/sleep/dailySleepSummariesByDate?startDate=…&endDate=…` (31 jours au maximum) |
| VFC | `/hrv-service/hrv/{calendarDate}` ou `/hrv-service/hrv/daily/{fromDate}/{toDate}` |
| SpO₂ | `/wellness-service/wellness/daily/spo2acclimation/{calendarDate}` |
| Activités bien-être | `/wellnessactivity-service/activity/summary/range/{fromDate}/{untilDate}` (dates incluses) |
| Journal alimentaire | `/nutrition-service/food/logs/{date}` (`includeGoals`, `includeNutrition`, `includeMedia`, `mealId` facultatifs) |
| Cycle | `/periodichealth-service/menstrualcycle/summary/{calendarDate}` |
| Jour du cycle | `/periodichealth-service/menstrualcycle/dayview/{calendarDate}` (`todayDate`, `reducedResponse`, `skipbm`, `includeAnalysis` facultatifs) |
| Paramètres | `/userprofile-service/userprofile/settings` |
| Zones FC | `/biometric-service/heartRateZones/v2?generateDefaults=false` (défaut API `true`) |
| Poids et moyennes | `/weight-service/weight/range/{startDate}/{endDate}?includeAll=true` (dates locales incluses, `includeAll` vaut `false` par défaut) |
| Toutes les pesées | `/weight-service/weight/all/{startDate}/{endDate}` (364 jours au maximum) |

## Santé et bien-être (33)

Le code des outils et les méthodes privées déléguées définissent la granularité à préserver. [Outils Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/health_wellness.py) · [Méthodes v0.3.2](https://github.com/cyberjunky/python-garminconnect/blob/0.3.2/garminconnect/__init__.py#L648) · [Swagger Garmin](https://developer.garmin.com/gc-developer-program/personal/swagger-docs)

| Outil enregistré | Statut | Route officielle et écart à traiter |
| --- | --- | --- |
| `get_stats` | Dérivé | Résumé simple. Projeter les pas, distances, étages, calories, durées, intensité, FC, stress, Body Battery, SpO₂ et respiration selon les noms et règles d’arrondi de l’outil. Le cumul du jour en cours est partiel. |
| `get_stats_range` | Dérivé | Résumés v2. Projeter `calories.burnedTotal`, `burnedActive`, `burnedResting` et `movement.steps.value`. Garder la grille inclusive, les données manquantes à `null` et le marquage du jour en cours. Taxuspt limite l’outil à 28 jours et lit deux ressources privées `statsType`. |
| `get_energy_balance` | Dérivé | Journal alimentaire par jour, Résumés v2 et Toutes les pesées. Reprendre le calcul Taxuspt, les compteurs d’aliments par repas et les exclusions des jours sans saisie et des dépenses du jour courant. Employer chaque mesure avec poids et masse grasse, corriger l’unité d’entrée, appliquer le contrôle de dispersion des pesées puis la régression et son erreur-type. Projection estimative propre à Taxuspt, au maximum 61 jours. |
| `get_user_summary` | Direct | Résumé simple, qui documente la forme `UserDailySummaryDto` et évite le `displayName` dans la route privée. Le drapeau privé `privacyProtected` utilisé par le client v0.3.2 n’est pas un champ contractuel public. |
| `get_body_composition` | Dérivé | Toutes les pesées et Poids et moyennes. Reconstituer la liste `dateWeightList` et le bloc `totalAverage` utilisés par les consommateurs. L’ancien `/weight/dateRange` et ses champs non documentés ne bénéficient d’aucune garantie de compatibilité brute. |
| `get_stats_and_body` | Dérivé | Résumé simple et Poids et moyennes à date unique. Le client source fusionne à plat les statistiques et `totalAverage`, avec priorité aux clés de composition. |
| `get_steps_data` | Partiel | Pas ou Résumé pour le total journalier. Les mesures par tranches de 15 minutes du `dailySummaryChart` ne sont pas documentées. |
| `get_daily_steps` | Direct | Pas, même série quotidienne. Découper par fenêtres d’au plus 28 jours pour conserver les demandes longues de la bibliothèque. |
| `get_training_readiness` | Absent | Aucune série ni score officiel de Training Readiness. Le score propriétaire et ses facteurs ne se déduisent pas contractuellement du sommeil, de la VFC et du stress exposés. |
| `get_body_battery` | Dérivé | Batterie utilise la même route. Le DTO expose charge, décharge, `bodyBatteryActivityEvent` et feedback dynamique. Convertir les millisecondes en minutes et reprendre la projection Taxuspt. |
| `get_body_battery_events` | Partiel | Batterie ou Résumé pour leurs `bodyBatteryActivityEvent` / `bodyBatteryActivityEventList`. Aucun contrat pour le flux privé distinct `/bodyBattery/events/{date}`, dont la bibliothèque mentionne sommeil, activités détectées et siestes. |
| `get_blood_pressure` | Absent | Le contrat ne documente aucune série ou valeur de tension artérielle. |
| `get_floors` | Partiel | Résumé simple pour les totaux d’étages et les distances verticales du jour. Le graphique intrajournalier `/floorsChartData/daily/{date}` est absent. Résumés v2 tronquent les fractions d’étage selon leur propre schéma. |
| `get_rhr_day` | Dérivé | FC ou Résumé pour `restingHeartRate` et éventuellement la moyenne sur 7 jours. Recréer une sortie centrée sur la mesure du jour. Le wrapper privé `metricId=60` est distinct. |
| `get_heart_rates` | Direct | FC comprend la série `heartRateValues`, ses descripteurs, les valeurs aberrantes et leurs seuils. Adapter les dates et timestamps publics `ConnectDate`/`ConnectTimestamp`, dont la date locale explicite. Le Swagger ne promet pas la cadence nominale de deux minutes dans tous les cas. |
| `get_heart_rates_summary` | Dérivé | FC. Extraire min, max, repos et moyenne de repos sur 7 jours. Recalculer moyenne et compteur à partir des lectures positives et de leur descripteur. |
| `get_hydration_data` | Dérivé | Hydratation pour `{date}/{date}`, puis sélectionner le jour. Le DTO documente volume, objectif, horodatage de dernière entrée, pertes par sudation et apport pendant l’activité. |
| `get_sleep_data` | Partiel | Sommeil expose `DailySleepDTO` avec durée, stades agrégés, scores, SpO₂, respiration et siestes. L’enveloppe privée `dailySleepData/{displayName}` et ses séries de sommeil ne sont pas couvertes. |
| `get_sleep_summary` | Partiel | Sommeil pour durée, horaires, scores, stades, stress et SpO₂. VFC `lastNightAvg` et FC/Résumé pour les compléments VFC/repos du jour, sous réserve d’alignement des dates. Le compteur `restlessMomentsCount` n’est pas documenté. |
| `get_sleep_summary_range` | Partiel | Sommeils, découpage par tranches de 31 jours pour garder les 90 nuits du contrat Taxuspt. VFC et Résumés v2 en complément. Le compteur de mouvements reste absent et les nuits sans mesure doivent être distinguées des nuits à valeur zéro. |
| `get_stress_data` | Partiel | Stress pour score et durées quotidiennes. Batterie fournit sa propre série. Aucune série intrajournalière `stressValuesArray` documentée. |
| `get_stress_summary` | Partiel | Résumé simple pour minimum de données utile (moyenne, maximum) et Stress pour durées par zone. Les proportions calculées par Taxuspt sur les échantillons positifs et leur compteur ne sont pas disponibles sous cette définition. |
| `get_respiration_data` | Partiel | Résumé, Résumés v2 et Sommeil fournissent des agrégats journaliers ou nocturnes. La série intrajournalière est absente. |
| `get_respiration_summary` | Dérivé | Résumé simple pour min/max et moyenne à l’éveil, Sommeil pour `averageRespirationValue` nocturne. Aligner la date du jour avec la nuit attribuée à cette date. |
| `get_spo2_data` | Dérivé | SpO₂ couvre moyenne, min, dernière lecture locale, moyenne sur 7 jours, moyenne de sommeil et agrégats horaires projetés par le code. L’endpoint public ajoute `acclimation` au chemin privé. |
| `get_all_day_stress` | Partiel | Même route privée et mêmes écarts que `get_stress_data`. |
| `get_all_day_events` | Partiel | Activités bien-être pour des résumés d’événements d’activité, Batterie pour ses événements et Sommeil pour ses siestes. Aucun contrat ne donne l’intégralité ni la taxonomie exacte du flux privé `dailyEvents`. |
| `get_lifestyle_logging_data` | Absent | Pas d’accès documenté au journal de comportements `dailyLifestyleLogging`. |
| `get_weekly_steps` | Dérivé | Pas par tranches de 28 jours. Grouper en semaines, sommer et moyenner par jour de données, garder l’ordre descendant et la limite applicative de 52 semaines. Valider l’ancre des semaines, la distinction absence/zéro et l’équivalence du décompte `wellnessDataDaysCount` en s’appuyant sur la couverture `includesWellnessData` de Résumés v2 si nécessaire. |
| `get_weekly_stress` | Partiel | Stress permet un calcul hebdomadaire sur les scores et durées journaliers. L’algorithme d’agrégation du `value` natif au niveau semaine n’est pas établi. |
| `get_weekly_intensity_minutes` | Dérivé | Résumés v2 expose modéré, vigoureux et objectif hebdomadaire. Agréger par semaine puis appliquer `modéré + 2 × vigoureux`, en gardant la convention de date et la limite de 52 semaines de l’outil. Valider l’ancre et les changements d’objectif en cours de semaine. |
| `get_morning_training_readiness` | Absent | Le client privé choisit un instantané de Training Readiness au réveil, avec repli sur le premier de la journée. Les instantanés ne sont pas documentés. |
| `get_recovery_time_remaining` | Absent | Le code lit `recoveryTime` dans Training Readiness ou Morning Readiness et retombe pour aujourd’hui sur un décompte basé sur le `recoveryTime` d’activités récentes. Ce champ est absent des schémas publics de liste/détail d’activité. `recoveryHeartRate` est une mesure distincte. |

## Santé féminine (3)

[Outils Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/womens_health.py) · [Client v0.3.2](https://github.com/cyberjunky/python-garminconnect/blob/0.3.2/garminconnect/__init__.py#L2784) · [Swagger Garmin](https://developer.garmin.com/gc-developer-program/personal/swagger-docs)

| Outil enregistré | Statut | Route officielle et écart à traiter |
| --- | --- | --- |
| `get_pregnancy_summary` | Partiel | Cycle et Jour du cycle documentent des indicateurs de grossesse comme `pregnancyCycle`, `dueDate`, `deliveryDate`, `numberOfWeek` et des objectifs hebdomadaires de poids dans la vue de jour. Le `pregnancysnapshot` privé, sans date, reste absent du contrat public. |
| `get_menstrual_data_for_date` | Direct | Jour du cycle, même chemin, avec `dayLog`, `daySummary` et `weightGoals`. `todayDate` définit la référence utilisée pour les prédictions. Le SDK le transmet explicitement, même si Swagger le rend facultatif. |
| `get_menstrual_calendar_data` | Partiel | Cycle, ou Jour du cycle, un appel par date puis regroupement par cycle. Les périodes chevauchantes, les prédictions et la parité avec le calendrier privé par fenêtres de 92 jours et sa déduplication restent à établir. |

## Nutrition (15)

[Outils Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/nutrition.py) · [Swagger Garmin](https://developer.garmin.com/gc-developer-program/personal/swagger-docs)

| Outil enregistré | Statut | Route officielle et écart à traiter |
| --- | --- | --- |
| `get_nutrition_daily_food_log` | Direct | Journal alimentaire, même chemin en lecture et date locale. Inclut consommations, objectifs, aliments journalisés et détails par repas, avec paramètres facultatifs. |
| `get_nutrition_summary_between_dates` | Dérivé | Journal alimentaire une fois par date. Extraire `dailyNutritionContent` et compter `mealDetails[].loggedFoods[]`. Conserver la distinction entre jour non saisi, log partiel et données absentes. Le plafond de 61 jours dans Taxuspt ne transforme pas cette séquence en appel de plage natif. |
| `get_nutrition_daily_meals` | Partiel | Journal alimentaire, projection de `mealDetails[].meal`, `mealId`, `mealNutritionContent` et `mealNutritionGoals`. `defaultMealName` est explicitement réservé à d’autres routes dans le modèle `MealDTO`, dont `/nutrition-service/meals/{date}`, absent de la surface Personal. La couverture des repas vides reste à vérifier. |
| `get_nutrition_daily_settings` | Partiel | Journal alimentaire avec objectifs, `dailyNutritionGoals` comprend calories et macros, normales et ajustées. La configuration complète du plan de `/nutrition-service/settings/{date}`, par exemple la date cible du plan, n’est pas documentée via le journal. |
| `set_nutrition_daily_settings` | Absent | Le PUT privé de réglages n’a pas d’équivalent. |
| `search_foods` | Absent | Le catalogue Garmin/FatSecret avec pagination n’est pas publié. |
| `get_custom_foods` | Absent | L’inventaire et la recherche selon `customFood` sont absents. Les aliments figurant dans le journal ne constituent pas cet inventaire. |
| `get_custom_food_serving_units` | Absent | Aucune liste officielle des unités autorisées pour créer des aliments personnalisés. |
| `create_custom_food` | Absent | Création au catalogue privé. |
| `update_custom_food` | Absent | Modification au catalogue privé. |
| `delete_custom_food` | Absent | Suppression au catalogue privé. |
| `log_custom_food` | Absent | Écriture dans le journal à partir d’un aliment et de sa portion. |
| `log_food` | Absent | Ajout rapide avec calories et macros. |
| `delete_food_log` | Absent | Suppression d’une entrée du journal. |
| `upsert_and_log` | Absent | Recherche ou création privée d’un aliment, suivie d’une écriture dans le journal. |

## Poids (5)

`DateWeightDTO.weight` est documenté en kilogrammes, alors que le code Taxuspt traite le poids privé brut comme des grammes et le divise par 1 000 pour afficher des kilogrammes. `WeightAverageDTO.weight`, `boneMass` et `muscleMass` n’ont pas d’unité explicite dans leurs propriétés documentées. La valeur de `sourceType` utilisée par les deux outils de lecture et les identifiants `samplePk` utilisés par la suppression ne figurent pas dans `DateWeightDTO`. Valider les unités et les champs optionnels sur des réponses autorisées avant d’affirmer la parité. [Outils Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/weight_management.py) · [Client Garmin](https://github.com/garmin/connect-personal-api-python/blob/958ef5883ba3b74f1b27a5c00dafc4e96351e723/src/garmin_personal_api/client.py#L105) · [Swagger Garmin](https://developer.garmin.com/gc-developer-program/personal/swagger-docs)

| Outil enregistré | Statut | Route officielle et écart à traiter |
| --- | --- | --- |
| `get_weigh_ins` | Partiel | Poids et moyennes, même chemin avec `includeAll=true`, apporte les pesées et la moyenne. `source_type` et les unités des mesures dérivées doivent être traités selon les limites ci-dessus. |
| `get_daily_weigh_ins` | Partiel | Poids et moyennes pour le même jour aux deux bornes, puis projection. Le `dayview` privé est absent et la propriété `source_type` n’est pas garantie. |
| `delete_weigh_ins` | Absent | Le DELETE privé par date et version, après sélection des mesures de `dayview`, n’est pas publié. |
| `add_weigh_in` | Absent | Le POST privé `/weight-service/user-weight` n’est pas publié. |
| `add_weigh_in_with_timestamps` | Absent | Même POST privé avec horodatages local et GMT. |

## Profil (6)

[Outils Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/user_profile.py) · [Client v0.3.2](https://github.com/cyberjunky/python-garminconnect/blob/0.3.2/garminconnect/__init__.py#L502) · [Swagger Garmin](https://developer.garmin.com/gc-developer-program/personal/swagger-docs)

| Outil enregistré | Statut | Route officielle et écart à traiter |
| --- | --- | --- |
| `get_full_name` | Absent | Paramètres expose `displayName`. Le `fullName` social lu et mémorisé par le client privé est un champ différent. |
| `get_unit_system` | Dérivé | Paramètres expose `measurementSystem`. Projeter dans le champ `unit_system`. |
| `get_user_profile` | Partiel | Paramètres couvre une partie des préférences. Le bloc plus large `/userprofile-service/userprofile/user-settings` utilisé par l’outil est absent. |
| `get_userprofile_settings` | Direct | Paramètres, même chemin, schéma `UserSettingsDTO`. |
| `get_heart_rate_zones` | Dérivé | Zones FC avec `generateDefaults=false` pour garder la lecture des configurations enregistrées. Filtrer localement par `sport`, en conservant l’alias `generic` → `DEFAULT`. Le DTO v2 expose la méthode, les bornes et les FC de référence. |
| `set_heart_rate_zones` | Absent | L’écriture des paramètres de FC par sport n’a pas d’équivalent dans la surface publique. |
