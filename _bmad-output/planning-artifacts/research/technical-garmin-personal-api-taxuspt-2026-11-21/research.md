---
title: "Outils Taxuspt et Garmin Connect Personal API"
type: technical
topic: "Porter les outils Taxuspt dans garmin-mcp Java"
decision: "Choisir les portages et les ajouts réalisables avec une clé Personal API"
source: "Code Taxuspt et contrat OpenAPI Garmin"
status: complete
verified_claims: 3
unverified_claims: 1
created: "2026-11-21"
updated: "2026-11-21"
---

# Outils Taxuspt et Garmin Connect Personal API

## Résultat

**69 outils Taxuspt peuvent être repris ou adaptés en Java** avec la Personal API. Le relevé compte **35 portages fonctionnels** (17 directs et 18 avec calcul ou assemblage Java), **34 portages partiels**, **1 réglage local** sans appel Garmin et **87 outils sans portage documenté**. La matrice donne le chemin officiel pour chaque correspondance à l’aide du répertoire des 28 routes. [1] [3] [4]

Pour étendre le catalogue, les gains les plus nets sont la recherche d’activités avec filtres natifs, les étapes d’un entraînement associé à une activité, les séances bien-être par période et l’historique des objectifs de poids. [1] [3]

Les nombres mesurent la couverture documentée. La parité des réponses et la disponibilité des champs selon l’appareil restent à valider sur un compte. [3] [4] [6]

## Périmètre

Taxuspt est figé au commit `a2ca5fc` du 20 octobre 2026. Le code déclare **157 outils distincts dans 16 modules**, hors cinq ressources MCP de modèles de séance. Le décompte vient des décorateurs `@app.tool` des modules effectivement enregistrés, avec le profil par défaut. Le README de ce commit indique encore 155. Ce commit épingle `garminconnect==0.3.2`. [1] [2]

Le Swagger Garmin Connect Personal API 1.0.0, extrait du composant servi par Garmin le 21 novembre 2026, contient **28 opérations de lecture `GET`**. Les chemins ci-dessous sont relatifs à `https://personal.connectapi.garmin.com`. Le `POST` d’échange de clé contre un jeton est documenté séparément. Le client officiel de Garmin couvre 26 des 28 lectures avec des méthodes dédiées. Le résumé quotidien v1 et toutes les pesées figurent aussi dans le Swagger. [3] [4] [5]

Les correspondances mesurent la couverture du contrat fonctionnel de l’outil. **Direct** signifie que les données utiles sont documentées, avec adaptation des champs si nécessaire. **Calcul** signifie que Java reconstruit le résultat à partir de ces données. **Partiel** indique les champs ou le comportement à retirer ou à renommer. Les disponibilités par compte, appareil et activité restent à vérifier en production. [1] [3]

## Inventaire des routes officielles

Les références **A1–A6**, **H1–H21** et **P1** permettent de retrouver le chemin exact de chaque outil dans la matrice. Les paramètres présentés après `?` sont les paramètres utiles aux portages. [3] [4]

| Réf. | Opération officielle | Données et paramètres |
|---|---|---|
| A1 | `GET /activitylist-service/activities/search/activities` | `start`, `limit`, dates, type/sous-type, résultats paginés et filtres supplémentaires. |
| A2 | `GET /activity-service/activity/{activityId}` | Détail, métadonnées, mesures globales et synthèses de splits. |
| A3 | `GET /download-service/files/activity/{activityId}` | Fichier original dans une archive ZIP. |
| A4 | `GET /activity-service/activity/activityTypes/simple` | Catalogue simple des types et sous-types d’activité. |
| A5 | `GET /activity-service/activity/{activityId}/workouts` | Entraînements associés à l’activité et étapes. Un tableau vide est prévu. |
| A6 | `GET /wellnessactivity-service/activity/summary/range/{fromDate}/{untilDate}` | Événements bien-être, synthèses et temps en zones par plage inclusive. |
| H1 | `GET /usersummary-service/usersummary/daily?calendarDate=…` | Résumé d’un jour, notamment pas, calories, étages, stress, FC, Body Battery et cibles. |
| H2 | `GET /usersummary-service/v2/dailysummaries?startDate=…&endDate=…` | Résumés structurés, jusqu’à un an. |
| H3 | `GET /usersummary-service/stats/steps/daily/{startDate}/{endDate}` | Totaux et objectifs de pas, jusqu’à 28 jours par requête. |
| H4 | `GET /usersummary-service/stats/stress/daily/{startDate}/{endDate}` | Agrégats de stress par jour, jusqu’à 28 jours par requête. |
| H5 | `GET /usersummary-service/stats/hydration/daily/{startDate}/{endDate}` | Hydratation par jour, jusqu’à 31 jours par requête. |
| H6 | `GET /sleep-service/sleep/dailySleep?date=…` | Sommeil par jour, scores, besoins, siestes et mesures agrégées. |
| H7 | `GET /sleep-service/sleep/dailySleepSummariesByDate?startDate=…&endDate=…` | Résumés de sommeil jusqu’à 31 jours par requête. |
| H8 | `GET /wellness-service/wellness/dailyHeartRate?date=…` | Valeurs journalières de FC, série et statistiques de repos. |
| H9 | `GET /wellness-service/wellness/bodyBattery/reports/daily?startDate=…&endDate=…` | Rapports et événements inclus, paramètre `chunks` facultatif, jusqu’à 31 jours. |
| H10 | `GET /wellness-service/wellness/daily/spo2acclimation/{calendarDate}` | SpO₂ journalière, échantillons, moyennes horaires et contexte d’altitude. |
| H11 | `GET /hrv-service/hrv/{calendarDate}` | VFC quotidienne, avec le détail des lectures. |
| H12 | `GET /hrv-service/hrv/daily/{fromDate}/{toDate}` | Résumés de VFC par plage. |
| H13 | `GET /biometric-service/heartRateZones/v2` | Zones FC par sport, option `generateDefaults`. |
| H14 | `GET /fitnessage-service/fitnessage/v2/{calendarDate}` | Âge de forme et composantes, option `fullHistoryScan`. |
| H15 | `GET /metrics-service/metrics/maxmet/daily/{startDate}/{endDate}` | VO₂ max daté, filtre `sport` répétable. |
| H16 | `GET /weight-service/weight/all/{startDate}/{endDate}` | Toutes les entrées de poids et données de composition disponibles, jusqu’à 364 jours. |
| H17 | `GET /weight-service/weight/range/{startDate}/{endDate}` | Résumés quotidiens, moyenne et pesées adjacentes, option `includeAll`. |
| H18 | `GET /goal-service/goal/user/effective/weightgoal/{startDate}/{endDate}` | Objectifs et fourchettes de poids applicables pendant la plage. |
| H19 | `GET /nutrition-service/food/logs/{date}` | Journal nutritionnel, objectifs, nutriments, médias et filtre `mealId`. |
| H20 | `GET /periodichealth-service/menstrualcycle/dayview/{calendarDate}` | Détails du jour, options `todayDate`, `reducedResponse`, `skipbm`, `includeAnalysis`. |
| H21 | `GET /periodichealth-service/menstrualcycle/summary/{calendarDate}` | Résumé de cycle à une date. |
| P1 | `GET /userprofile-service/userprofile/settings` | Réglages du profil, unités, locale et préférences. |

## Outils Taxuspt à reprendre ou à adapter

### Entraînement, performance et objectifs

Les routes renvoient à l’inventaire officiel ci-dessus. [3] [10] [11]

| Outil Taxuspt | Couverture | Routes | Adaptation Java |
|---|---|---|---|
| `get_progress_summary_between_dates` | Calcul | A1 | Paginer et grouper par type. Calculer nombre, somme, moyenne, min et max pour distance, dénivelé, durée, temps en mouvement et calories. Fixer la règle sur les sous-activités et les valeurs manquantes. |
| `get_training_effect` | Partiel | A2, A1 | Conserver les effets aérobie et anaérobie, le libellé et la charge de l’activité. Les réponses documentées ne donnent ni le temps de récupération ni la condition de performance. |
| `get_hrv_data` | Direct | H11 | Résumé de VFC et lectures détaillées si demandées. |
| `get_hrv_trend` | Calcul | H12 | Une lecture de plage donne les valeurs nocturnes, la moyenne hebdomadaire, le pic sur 5 min et le statut. Calculer la moyenne de période côté Java. |
| `get_fitnessage_data` | Direct | H14 | Sélectionner la mesure dans le tableau REST v2 et projeter l’âge et ses composantes. Garder la distinction entre mesure indisponible et valeur présente. |
| `get_vo2max_trend` | Calcul | H15 | Lire les mesures historiques, distinguer sport et date, signaler si une valeur a été reportée sur un jour sans mesure. Le repli Taxuspt sur le profil courant demande une adaptation. |
| `get_respiration_trend` | Calcul | H1 + H6 ou H7 | Associer moyenne éveillée et extrema du jour avec moyenne nocturne. Préserver la distinction jour/nuit et les dates manquantes. H2 permet une variante avec agrégats journaliers. |
| `get_cycling_ftp` | Partiel | A1, A2 | Créer une lecture de la FTP observée dans les activités cyclistes. L’estimation biométrique courante, sa provenance et son indicateur d’ancienneté sont hors contrat. |
| `get_lactate_threshold` | Partiel | A1, A2, H13 | Lire vitesse et FC de seuil présentes dans les activités et FC de seuil utilisée par les zones par sport. L’historique biométrique dédié et le statut de la dernière mesure sont hors contrat. |
| `get_goals` | Partiel | H18, H1 | Objectifs de poids pour une plage et cibles journalières documentées. Les catégories générales d’objectifs actifs, futurs et passés sont hors contrat. |
| `get_personal_record` | Partiel | A1, A2, H3 | Calculer des records sur une période explicite à partir des meilleurs segments publiés, temps, distances et pas. Les marques de record d’une activité ne suffisent pas à reconstituer le palmarès Garmin. |

### Santé, sommeil, nutrition et profil

Les résumés officiels couvrent plusieurs outils qui appellent des services différents chez Taxuspt. [3] [7] [8] [9]

| Outil Taxuspt | Couverture | Routes | Adaptation Java |
|---|---|---|---|
| `get_stats` | Direct | H1 | Reprendre la sélection des champs du résumé du jour. |
| `get_stats_range` | Calcul | H2 | Projeter les calories et les pas par date. Garder les jours sans données distincts des zéros. |
| `get_user_summary` | Direct | H1 | Répondre avec le modèle officiel pour le jour, sans `displayName` dans le chemin. |
| `get_stats_and_body` | Calcul | H1 + H16 ou H17 | Fusionner le résumé du jour et les mesures de composition disponibles. |
| `get_body_composition` | Calcul | H17 + H16 | Reconstituer les résumés et la liste de mesures de composition. Le poids individuel officiel est en kg. Les unités des moyennes restent à valider. |
| `get_steps_data` | Partiel | H3 | Le total du jour, la distance et l’objectif sont disponibles. La série intrajournalière de l’outil Taxuspt est hors contrat. |
| `get_daily_steps` | Direct | H3 | Découper les grandes plages en fenêtres de 28 jours. |
| `get_weekly_steps` | Calcul | H3, éventuellement H2 + P1 | Calculer totaux, moyennes et jours couverts, avec début de semaine et dénominateur explicites. |
| `get_floors` | Partiel | H1, H2 | Total et objectif, sans le graphique intrajournalier d’étages de Taxuspt. |
| `get_rhr_day` | Direct | H8, H1 | FC de repos du jour et moyenne de repos sur sept jours, avec adaptation de l’enveloppe. |
| `get_heart_rates` | Direct | H8 | Série et statistiques documentées pour la date. |
| `get_heart_rates_summary` | Calcul | H8 | Reprendre les min, max et repos. Calculer la moyenne et le nombre d’échantillons valides. |
| `get_body_battery` | Direct | H9 | Rapports quotidiens avec charge, décharge, événements et retour dynamique. Projeter les événements, convertir leurs durées en minutes et limiter les séries au besoin. |
| `get_body_battery_events` | Partiel | H9, H1, H2 | Rapporter les événements Body Battery inclus dans ces réponses, avec durée et impact lorsqu’ils sont présents. Le contrat de la chronologie dédiée diffère. |
| `get_hydration_data` | Direct | H5 | Même date aux deux bornes pour l’hydratation, l’objectif et les pertes estimées. |
| `get_sleep_data` | Partiel | H6 | Sommeil, phases agrégées, scores et besoins disponibles. Le Swagger ne publie pas l’enveloppe brute avec les différentes séries de Taxuspt. |
| `get_sleep_summary` | Partiel | H6 + H11 + H8 | Durées, scores et respiration. Joindre VFC et FC de repos avec dates alignées. `restlessMomentsCount` est hors schéma. |
| `get_sleep_summary_range` | Partiel | H7 + H12 + H2 | Même synthèse sur 31 jours de sommeil par requête. Joindre VFC et FC de repos. Distinguer nuits sans mesure et valeurs nulles. |
| `get_stress_data` | Partiel | H1, H2, H4 | Moyennes, maximums, durées et répartition documentés, sans série intrajournalière de stress. |
| `get_stress_summary` | Partiel | H1, H2 | Reprendre moyenne et maximum. La répartition officielle est une part de durée. Taxuspt calcule une part d’échantillons et retourne leur nombre. |
| `get_all_day_stress` | Partiel | H1, H2, H4 | Exposer un résumé sur la journée. Les échantillons de la journée sont hors contrat. |
| `get_weekly_stress` | Partiel | H4 ou H2 | Construire un agrégat hebdomadaire et préciser la pondération et le traitement des jours absents. La définition exacte du score hebdomadaire Taxuspt reste indéterminée. |
| `get_weekly_intensity_minutes` | Calcul | H1 ou H2 | Grouper les minutes modérées et vigoureuses. Pour le total pondéré de Taxuspt, compter deux fois les minutes vigoureuses. Les cibles sont à interpréter selon la semaine. |
| `get_respiration_data` | Partiel | H1 + H6, H2 | Agrégats disponibles, série respiratoire intrajournalière hors contrat. |
| `get_respiration_summary` | Calcul | H1 + H6 | Associer moyenne éveillée et extrema avec moyenne nocturne. |
| `get_spo2_data` | Direct | H10 | Résumé, moyenne sur sept jours, mesure récente, moyenne durant le sommeil et moyennes horaires selon disponibilité. |
| `get_all_day_events` | Partiel | A6, H9, H6 | Rapprocher événements bien-être, Body Battery et siestes. Les types et la couverture de leur chronologie restent propres à ces routes. |
| `get_weigh_ins` | Partiel | H16, H17 | Pesées, moyennes et variations disponibles. Garder les kg. La provenance `source_type` que renvoie Taxuspt est hors schéma. |
| `get_daily_weigh_ins` | Partiel | H16 ou H17 | Même jour aux deux bornes. Récupérer toutes les mesures disponibles. La provenance `source_type` est hors schéma. |
| `get_energy_balance` | Calcul | H19 + H2 + H16 | Lire le journal chaque jour, la dépense estimée et les pesées. Reprendre le calcul robuste et ses conditions de couverture. Signaler les jours sans journal et la nature estimée du résultat. |
| `get_nutrition_daily_food_log` | Direct | H19 | Journal d’une date, nutriments, objectifs et détails des repas. |
| `get_nutrition_summary_between_dates` | Calcul | H19 par jour | Agréger calories et macros, compter les aliments par jour. Le Swagger ne fournit pas de lecture du journal par plage en une requête. |
| `get_nutrition_daily_meals` | Partiel | H19 | Projeter `mealDetails` et leurs totaux. `mealId` filtre le repas à la lecture. Le nom par défaut du repas et la présence des repas vides restent hors garantie. |
| `get_nutrition_daily_settings` | Partiel | H19 | Fournir les objectifs caloriques et macros via `dailyNutritionGoals`. La configuration complète du plan est hors contrat. |
| `get_menstrual_data_for_date` | Direct | H20 | Lecture du jour avec options de référence et d’analyse. |
| `get_menstrual_calendar_data` | Partiel | H20 et H21, par date | Reconstruire une chronologie explicitement bornée. Aucun contrat de plage ne garantit la même couverture que le calendrier Taxuspt. |
| `get_pregnancy_summary` | Partiel | H21, H20 | Si présents, lire terme estimé, semaine, phase et fourchettes de poids. Le snapshot de grossesse complet est hors contrat. |
| `get_unit_system` | Direct | P1 | Mapper `measurementSystem` et conserver les cas distingués par Garmin. |
| `get_userprofile_settings` | Direct | P1 | Lire réglages et préférences documentés. |
| `get_user_profile` | Partiel | P1, éventuellement A1 ou A2 | Limiter la réponse aux réglages et à l’identité disponible. Le profil social complet est hors contrat. |
| `get_full_name` | Partiel | A1 ou A2 | Utiliser `ownerFullName` ou `metadataDTO.userInfoDto.fullname` d’une activité du propriétaire si présent. P1 fournit le pseudonyme `displayName`. |
| `get_heart_rate_zones` | Direct | H13 | Lire par sport et filtrer localement. Passer `generateDefaults=false` pour viser les zones enregistrées de Taxuspt. |

### Activités, fichiers et analyses FIT

Le ZIP officiel contient le fichier original. Le calcul à partir d’un FIT dépend des messages effectivement enregistrés, notamment des capteurs et du sport. Le SDK FIT Java de Garmin permet de décoder les messages. [3] [6] [12]

| Outil Taxuspt | Couverture | Routes | Adaptation Java |
|---|---|---|---|
| `get_activities` | Direct | A1 | Paginer avec `start` et `limit`, puis projeter le résumé. Un résultat de page pleine ne prouve pas qu’il reste des activités. |
| `get_activities_by_date` | Direct | A1 + A4 | Date locale inclusive, tri et pagination. Utiliser A4 pour distinguer type parent et sous-type. Le maximum de 200 lignes de l’outil Taxuspt reste à valider sur l’API officielle. |
| `get_activities_fordate` | Calcul | A1 | Donner la même date locale aux deux bornes et paginer. Vérifier les règles d’inclusion des sous-activités multisport. |
| `get_activity` | Partiel | A2 | Même résumé de base. L’indicateur de présence de splits `hasSplits` n’est pas dans le schéma officiel. |
| `count_activities` | Calcul | A1 | Compter les identifiants uniques sur toutes les pages. Le Swagger ne fournit ni total ni garantie d’instantané. |
| `get_activity_types` | Partiel | A4 | Retourner les clés et leur parent. L’API officielle ne garantit pas les identifiants numériques, noms et indicateurs de visibilité du catalogue Taxuspt. |
| `get_activity_splits` | Partiel | A3, A2 | Décoder les messages FIT `lap` et `length` si présents. A2 fournit les synthèses, sans garantir les enrichissements ou l’association aux étapes de l’outil Taxuspt. |
| `get_activity_typed_splits` | Partiel | A2, A3 | Exploiter les synthèses typées et les messages FIT compatibles. La granularité dépend du sport et du fichier. |
| `get_activity_split_summaries` | Partiel | A2, éventuellement A1 | Lire `splitSummaries` du détail, ou la version réduite de la liste. La forme exacte de l’ancienne réponse est à adapter. |
| `get_activity_hr_in_timezones` | Partiel | A1, A2, H13 | Lire `hrTimeInZone_1` à `_5` dans A1 et retrouver la date locale par A2 si nécessaire. Pourcentages calculables si le dénominateur est défini. Les seuils de H13 sont ceux configurés lors de la lecture. |
| `get_activity_power_in_timezones` | Partiel | A1, A2 | Lire `powerTimeInZone_1` à `_7` dans A1. Les seuils historiques en watts de la route Taxuspt sont hors contrat. |
| `get_activity_exercise_sets` | Partiel | A1, A3 | Lire les agrégats `summarizedExerciseSets` ou décoder les séries FIT enregistrées. Les éditions après synchronisation sont hors contrat. A5 expose des étapes de séance et répond à un besoin différent. |
| `download_activity_file` | Partiel | A3 | Donner accès à l’original ZIP et, si trouvé, au fichier FIT. Les formats GPX, TCX et CSV de Taxuspt nécessiteraient une conversion Java. |
| `get_activity_fit_messages` | Calcul | A3 | Extraire les messages FIT, filtrer les types et paginer les résultats côté Java. |
| `get_activity_fit_data` | Calcul | A3 + H17 si W/kg | Calculer les mesures de cyclisme, de puissance, de montée ou de dérive si les données sont présentes. Les pesées officielles sont déjà en kg. |
| `get_power_duration_curve` | Calcul | A1 + A3 | Refaire l’algorithme de Taxuspt sur les FIT. Une version plus légère peut lire les meilleurs efforts documentés dans A1 en signalant que le traitement des pauses et des trous peut différer. |
| `set_fit_download_dir` | Local | Aucun | L’outil persiste une préférence locale au serveur Python. Dans un serveur Java hébergé, cette préférence viserait le système de fichiers du serveur. |

## Capacités sans portage documenté

Le Swagger expose des lectures. Les outils ci-dessous exigent une écriture, un objet ou une métrique absents de la surface documentée. Certaines métriques peuvent inspirer des calculs Java distincts. [1] [3] [6] [7] [8] [9] [10] [11]

| Famille | Outils Taxuspt |
|---|---|
| Édition et données d’activité | `set_activity_name`, `set_activity_type`, `set_activity_description`, `set_activity_event_type`, `set_perceived_effort`, `set_activity_feel`, `set_activity_exercise_sets`, `create_manual_activity`, `delete_activity`, `get_activity_weather`, `get_activity_gear` |
| Calendrier | `get_calendar_events` |
| Défis, badges et prédiction de courses | `get_earned_badges`, `get_adhoc_challenges`, `get_available_badge_challenges`, `get_badge_challenges`, `get_non_completed_badge_challenges`, `get_inprogress_virtual_challenges`, `get_race_predictions` |
| Parcours enregistrés | `get_courses`, `get_course_details`, `download_course_gpx`, `upload_course`, `delete_course` |
| Saisie santé | `add_body_composition`, `set_blood_pressure`, `add_hydration_data` |
| Appareils | `get_devices`, `get_device_last_used`, `get_device_settings`, `get_primary_training_device`, `get_device_solar_data`, `get_device_alarms`, `push_workout_to_device` |
| Équipement personnel | `get_gear`, `add_gear_to_activity`, `remove_gear_from_activity` |
| Santé et récupération | `get_training_readiness`, `get_morning_training_readiness`, `get_recovery_time_remaining`, `get_blood_pressure`, `get_lifestyle_logging_data` |
| Nutrition | `set_nutrition_daily_settings`, `search_foods`, `get_custom_foods`, `get_custom_food_serving_units`, `create_custom_food`, `update_custom_food`, `delete_custom_food`, `log_custom_food`, `log_food`, `delete_food_log`, `upsert_and_log` |
| Indicateurs Garmin d’entraînement | `get_hill_score`, `get_endurance_score`, `get_training_status`, `request_reload`, `get_training_load_trend`, `get_training_load_balance`, `get_running_tolerance`, `get_running_tolerance_trend`, `get_acclimation` |
| Profil et poids en écriture | `set_heart_rate_zones`, `delete_weigh_ins`, `add_weigh_in`, `add_weigh_in_with_timestamps` |
| Création et programmation guidées | `create_walk_run_workout`, `create_run_workout`, `create_run_interval_workout`, `create_z2_walk_workout`, `create_strength_workout`, `schedule_week` |
| Bibliothèque et planification des séances | `get_workouts`, `get_workout_by_id`, `download_workout`, `upload_workout`, `upload_workouts`, `delete_workout`, `delete_workouts`, `get_scheduled_workouts`, `get_garmin_coach_workouts`, `get_training_plan_workouts`, `schedule_workout`, `schedule_workouts`, `reschedule_workout`, `unschedule_workout`, `unschedule_workouts` |

La charge par activité, la VFC et le sommeil restent disponibles pour un bilan maison. Les sources ne permettent pas de reproduire les scores Garmin de préparation, le temps de récupération ou les séries natives ATL, CTL, TSB et ACWR consommées par Taxuspt. Un export GPX à partir de la trace d’une activité enregistrée constitue une fonction différente de l’export d’un parcours sauvegardé. [3] [6] [10] [12]

## Ajouts au-delà des outils Taxuspt

| Ajout proposé | Routes | Apport |
|---|---|---|
| **Recherche avancée d’activités** | A1 + A4 | Étendre les recherches Taxuspt avec filtres natifs de texte, favoris, type d’événement, tri, multisport, durée, distance, dénivelé, puissance, carte et plongée. Les 32 paramètres comprennent pagination et contrôles. `requiredField` peut filtrer les activités où une mesure est présente avant une analyse. |
| **Entraînement associé à une activité** | A5 | Lire les étapes, cibles et répétitions de l’entraînement associé à l’enregistrement. Comparer les cibles des étapes aux mesures disponibles via A2 ou A3. |
| **Séances bien-être sur une période** | A6 | Lire les sessions avec horodatages, statistiques, temps en zones et métadonnées d’appareil disponibles. Cette vue par session complète la chronologie quotidienne de Taxuspt. |
| **Historique des objectifs de poids** | H18 | Afficher les objectifs et fourchettes applicables à chaque période et les croiser avec les pesées H16 ou H17. Taxuspt liste des objectifs généraux, sans lecture dédiée équivalente. |
| **Export brut et plat des pesées** | H16 | Exposer le DTO complet de chaque mesure dans une seule lecture native jusqu’à 364 jours. Taxuspt sait déjà aplatir les pesées multiples à partir de ses résumés. |
| **Bilan bien-être multi-jours** | H2, H7, H12 | Étendre le résumé calories/pas de Taxuspt avec les rubriques du résumé v2, les tendances de sommeil et de VFC. L’API accepte jusqu’à un an sur H2 et des plages dédiées sur H7 et H12. |
| **Nutrition ciblée par repas** | H19 | Filtrer avec `mealId` et réduire le volume en omettant les médias, objectifs ou données nutritionnelles selon la question. |
| **VO₂ max natif par sport et date** | H15 | Offrir la série datée brute avec `sport`, y compris l’aviron quand la réponse le contient. Taxuspt met en forme principalement course et vélo, avec des replis et des reports de valeurs. |
| **Sommeil enrichi et synthèse de cycle** | H6, H7, H21 | Exposer besoins de sommeil, fenêtre de sommeil optimale, siestes et résumé de cycle dédié. H20 permet aussi de sélectionner le niveau de détail et l’analyse du jour. |
| **Courbe de puissance sans charger les FIT** | A1 | Agréger les champs natifs `maxAvgPower_5` à `maxAvgPower_3600` pour les durées retenues et garder les activités qui portent les meilleurs efforts. Le Swagger ne décrit pas l’équivalence avec le calcul FIT de Taxuspt. |
| **Rapport Body Battery à résolution choisie** | H9 | Donner accès aux min/max par intervalle via `chunks` en complément des charges, décharges et événements déjà repris par Taxuspt. |

Pour le projet Java, le premier lot le plus court couvre **recherche avancée, détail d’activité, entraînement associé, sommeil et VFC**, puis le bilan bien-être. Ces fonctions utilisent des schémas publics avec peu de calcul local. Les analyses FIT et le bilan énergétique demandent des données représentatives, des règles de calcul explicites et davantage de cas de test. [3] [4] [6] [7] [12]

## Points à confirmer en production

- Vérifier les réponses pour un compte et des appareils représentatifs, les champs facultatifs et les réponses vides, notamment le `204` de la VFC. Confirmer les unités des moyennes de poids et l’alignement des dates entre sommeil, VFC et FC de repos. [3] [4]
- Pour les activités, vérifier la limite de page, l’inclusion multisport et le contenu du ZIP selon l’origine du fichier. Garmin documente une valeur par défaut de 99 activités, sans maximum contractuel pour `limit`. [3]
- Pour les outils qui agrègent plusieurs pages ou dates, valider la couverture et définir ce que le serveur renvoie si une requête échoue. Le contrat public consulté ne chiffre pas un quota général. [3] [5]

## Sources

| Réf. | Source et périmètre | Publication ou version | Consultation |
|---|---|---|---|
| [1] | [Taxuspt, modules et enregistrement MCP](https://github.com/Taxuspt/garmin_mcp/tree/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [2] | [Taxuspt, dépendances](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/pyproject.toml) et [README](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/README.md#tool-filtering) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [3] | [Garmin Developers, Swagger Personal API](https://developer.garmin.com/gc-developer-program/personal/swagger-docs/) et [composant officiel contenant OpenAPI](https://developer.garmin.com/component---src-pages-gc-developer-program-personal-swagger-docs-js-7ced9e541531dfdd103f.js) | API 1.0.0, date de publication du bundle non indiquée | 2026-11-21 |
| [4] | [Garmin, client officiel Python](https://github.com/garmin/connect-personal-api-python/blob/958ef5883ba3b74f1b27a5c00dafc4e96351e723/src/garmin_personal_api/client.py) | 2026-11-20, v1.1.0 | 2026-11-21 |
| [5] | [Garmin Developers, FAQ Personal API](https://developer.garmin.com/gc-developer-program/personal/personal-faq/) | Non datée | 2026-11-21 |
| [6] | [Taxuspt, outils d’activité](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/activity_management.py) et [d’analyse FIT](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/activity_analysis.py) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [7] | [Taxuspt, outils santé et bien-être](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/health_wellness.py) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [8] | [Taxuspt, nutrition](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/nutrition.py) et [pesées](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/weight_management.py) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [9] | [Taxuspt, profil](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/user_profile.py) et [santé féminine](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/womens_health.py) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [10] | [Taxuspt, entraînement et performance](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/training.py) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [11] | [Taxuspt, objectifs, records et défis](https://github.com/Taxuspt/garmin_mcp/blob/a2ca5fcac34524b83814fe2fcf3d479f8a75983f/src/garmin_mcp/challenges.py) | 2026-10-20, commit `a2ca5fc` | 2026-11-21 |
| [12] | [Garmin Developers, décodeur FIT](https://developer.garmin.com/fit/articles/cookbook/decoding_activity_files.html) et [SDK FIT Java](https://github.com/garmin/fit-java-sdk) | Documentation non datée | 2026-11-21 |

## Fraîcheur des sources

Le catalogue Taxuspt a été recontrôlé au 21 novembre malgré la date du commit, le 20 octobre. Pour actualiser cette cartographie, revoir le SDK officiel autour du 20 décembre 2026 et le Swagger autour du 21 décembre 2026, ou dès qu’une source change. Le 21 novembre sert de référence pour le Swagger, dont le bundle est non daté. [1] [3] [4]
