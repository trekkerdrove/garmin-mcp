# Catalogue des outils MCP Garmin

La cible compte **36 outils**, dont **35 ajouts**. Les références A, H et P désignent les chemins GET exacts du [relevé Garmin](../../planning-artifacts/research/technical-garmin-personal-api-taxuspt-2026-11-21/research.md#inventaire-des-routes-officielles). Les modes et filtres d’un même outil comptent pour un seul nom MCP.

| Livraison | Ajouts | Total cumulé | Contenu |
|---|---:|---:|---|
| Socle actuel | 0 | 1 | Sonde de connexion |
| Lot 1 | 12 | 13 | Activités, sommeil, fréquence cardiaque, VFC et résumés |
| Lot 2 | 21 | 34 | Vues spécialisées, agrégats, performance et suivis personnels |
| Lot 3 | 2 | 36 | Analyse et lecture d’échantillons FIT |

## Socle et analyses initiales

| Outil MCP | Capacité | Lot | Routes | Résultat et conditions |
|---|---|---:|---|---|
| `garmin_check_connection` | Socle | 0 | A1 | Même statut et même indicateur de présence. Au plus une activité demandée. Aucune mesure ou métadonnée renvoyée. |
| `running_distance` | CAP-1 | 1 | A1, A4 | Dates, nombre de courses et distance en km. Appliquer le calendrier et les règles multisport d’AD-4. |
| `compare_sleep_by_training_day` | CAP-2 | 1 | A1, H7 | Sommeil principal moyen, nuits mesurées ou manquantes et différence entraînement moins repos. Appliquer le rattachement et la classification des jours d’AD-4. |

## Activités enregistrées

| Outil MCP | Capacité | Lot | Routes | Résultat et conditions |
|---|---|---:|---|---|
| `garmin_search_activities` | CAP-3 | 1 | A1, A4 | Liste paginée. Dates locales, texte, favoris, tri, type/sous-type, événements, multisport, distances, durées, dénivelé, puissance, carte et plongée selon les paramètres du Swagger. Fournir l’offset suivant lorsque la page atteint sa limite et signaler que la fin n’est alors pas confirmée. |
| `garmin_get_activity` | CAP-3 | 1 | A2 | Détail synthétique par identifiant, date, sport, durée, distance et mesures présentes. Les vues de détail peuvent inclure FTP ou seuil lactique lorsqu’ils sont attachés à l’activité. |
| `garmin_get_activity_types` | CAP-3 | 1 | A4 | Clés et liens parent/enfant utilisables par les filtres de recherche. |
| `garmin_get_activity_workouts` | CAP-3 | 1 | A5 | Séances associées à l’activité et leurs étapes, cibles et répétitions disponibles. Une réponse vide signifie qu’aucune séance associée n’a été retournée. |
| `garmin_get_wellness_sessions` | CAP-3 | 1 | A6 | Séances bien-être sur une plage. Dates, type, mesures et temps en zones disponibles par session, avec une sélection bornée. |
| `garmin_get_activity_splits` | CAP-3 | 2 | A2 | Synthèses de segments par type et mesures associées. Les tours et échantillons enregistrés relèvent des outils FIT si le fichier les contient. |
| `garmin_get_activity_zones` | CAP-3 | 2 | A1, A2 | Durées dans les zones de fréquence cardiaque, de puissance ou les deux. A2 donne la date locale pour rechercher l’ID dans A1 avec `excludeChildren=false`. Les seuils historiques sont hors contrat. |
| `garmin_get_activity_strength_summary` | CAP-3 | 2 | A1, A2 | Séries, répétitions, volume et poids agrégés par exercice lorsqu’ils sont présents dans la recherche. Rechercher l’ID dans A1 avec `excludeChildren=false`. |

## Bien-être et récupération

| Outil MCP | Capacité | Lot | Routes | Résultat et conditions |
|---|---|---:|---|---|
| `garmin_get_daily_summary` | CAP-4 | 1 | H1 | Résumé d’un jour et objectifs quotidiens disponibles. Date courante signalée comme en cours. |
| `garmin_get_wellness_trends` | CAP-4 | 1 | H2 | Tendances par date pour les mesures choisies. Inclure calories, pas, mouvement, étages, minutes d’intensité, FC, stress, Body Battery, hydratation, respiration et SpO₂ selon couverture. Agrégation hebdomadaire de l’intensité. Minutes pondérées = modérées + deux fois les vigoureuses. |
| `garmin_get_sleep` | CAP-4 | 1 | H6, H7, H11, H12, H8, H2 | Jour ou plage. Durées, stades agrégés, réveils, stress nocturne, scores, SpO₂, besoin, fenêtre optimale et siestes quand Garmin les renseigne. L’enrichissement FC de repos et VFC est facultatif et indique les dates utilisées. Respiration nocturne disponible en détail. |
| `garmin_get_heart_rate` | CAP-4 | 1 | H8 | Fréquence cardiaque par date. Minimum, maximum, repos et moyenne de repos sur sept jours, éventuellement moyenne des lectures valides et échantillons bornés. |
| `garmin_get_hrv` | CAP-4 | 1 | H11, H12 | Jour ou plage. Moyenne nocturne et hebdomadaire, pic nocturne sur cinq minutes, statut et moyenne de période si calculée. Sur un seul jour, fournir une série bornée sur demande. Traiter le HTTP 204 comme absence. |
| `garmin_get_steps` | CAP-4 | 2 | H3 | Totaux, distances et objectifs par jour. Pour une vue par semaine, préciser le nombre de jours mesurés et le dénominateur de la moyenne. |
| `garmin_get_stress` | CAP-4 | 2 | H4, H1, H2 | Niveaux, durées et répartition selon disponibilité. Le mode hebdomadaire décrit sa pondération. La répartition s’exprime en durée et aucune série de stress intrajournalière n’est garantie. |
| `garmin_get_hydration` | CAP-4 | 2 | H5 | Jour ou plage. Volume en mL, objectif, perte estimée et dernier enregistrement si présent. |
| `garmin_get_body_battery` | CAP-4 | 2 | H9 | Charge et décharge. Événements et minima/maxima par intervalles en option, via `chunks`. Indiquer la résolution ou la troncature des intervalles retournés. |
| `garmin_get_respiration` | CAP-4 | 2 | H1, H2, H6, H7 | Agrégats d’éveil et de sommeil sur un jour ou une plage. Indiquer la portée de chaque agrégat. Les lectures intrajournalières de respiration sont hors contrat. |
| `garmin_get_spo2` | CAP-4 | 2 | H10 | Résumé quotidien, moyenne nocturne, minima et dernières valeurs disponibles. Moyennes horaires ou échantillons projetés en option bornée. |

## Performance

| Outil MCP | Capacité | Lot | Routes | Résultat et conditions |
|---|---|---:|---|---|
| `garmin_get_training_effect` | CAP-5 | 2 | A2 | Effet aérobie, anaérobie, libellé et charge de cette activité. Le délai de récupération restant est hors contrat. |
| `garmin_get_vo2max` | CAP-5 | 2 | H15 | Mesures datées par sport, notamment course, vélo ou aviron si présents. Signaler toute valeur reportée d’une autre date si le client demande une série continue. |
| `garmin_get_fitness_age` | CAP-5 | 2 | H14 | Âge de forme à la date, composantes sur demande et motif d’absence officiel si présent. |
| `garmin_get_heart_rate_zones` | CAP-5 | 2 | H13 | Configuration actuelle et bornes par sport. Les zones par défaut sont une option explicite. |
| `garmin_summarize_activities` | CAP-5 | 2 | A1, A4 | Comptages et agrégats par sport, pour une plage et des métriques choisies. Les totaux requièrent une lecture complète et une déduplication par ID. |
| `garmin_get_power_curve` | CAP-5 | 2 | A1, A4 | Meilleure puissance disponible par durée parmi les activités retenues, avec l’ID et la date. La courbe reflète les champs `maxAvgPower_*` de Garmin. |
| `garmin_get_best_efforts` | CAP-5 | 2 | A1, A4 | Meilleurs chronos sur les distances prises en charge ou meilleurs efforts en distance/dénivelé, sur la période choisie. Donner le sport, l’ID, la date et la source du calcul. |

## Suivis personnels

| Outil MCP | Capacité | Lot | Routes | Résultat et conditions |
|---|---|---:|---|---|
| `garmin_get_weight` | CAP-6 | 2 | H16, H17 | Vues pesées, résumé ou composition selon données. Garder les mesures multiples et distinguer le poids individuel en kg des moyennes à unité incertaine. |
| `garmin_get_weight_goals` | CAP-6 | 2 | H18 | Historique des objectifs et fourchettes de poids applicables à la plage demandée. |
| `garmin_get_nutrition` | CAP-6 | 2 | H19 | Jour ou plage bornée. Journal et objectifs à date unique, option par repas, totaux calories et macros et nombre d’aliments sur une plage. Distinguer une journée sans saisie d’un apport nul confirmé. |
| `garmin_get_cycle` | CAP-6 | 2 | H20, H21 | Résumé ou vues journalières bornées. Inclure l’analyse et les champs sensibles détaillés sur demande. |
| `garmin_get_profile_settings` | CAP-6 | 2 | P1 | Préférences et unités du compte, fuseau, locale et début de semaine. |

## FIT

| Outil MCP | Capacité | Lot | Routes | Résultat et conditions |
|---|---|---:|---|---|
| `garmin_analyze_activity_file` | CAP-7 | 3 | A3, H17 | Analyses ciblées des tours, du multisport, de la puissance, des montées, de la dérive cardio, de la dynamique cycliste ou des séries de musculation, selon les messages présents. W/kg uniquement si une pesée utilisable existe à la date ou avant. |
| `garmin_get_fit_samples` | CAP-7 | 3 | A3 | Projection bornée d’échantillons pour les mesures demandées, avec horodatage et unité. Inclure la couverture et un indicateur de troncature. Pas de coordonnées GPS ni de dump des messages. |
