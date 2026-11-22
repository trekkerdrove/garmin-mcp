---
id: SPEC-garmin-strength-workouts
companions:
  - contrat-musculation.md
sources: []
---

# Créer des séances de musculation dans Garmin MCP

## Pourquoi

Le propriétaire du compte peut déjà décrire une séance de course et l’enregistrer dans Garmin Connect après un aperçu. Il veut faire de même pour une séance de musculation et y retrouver les exercices, les séries, les charges et les repos sous une forme que Garmin peut exécuter. [Contrat et sources](contrat-musculation.md)

## Capacités

- **CAP-1 · Retrouver un exercice Garmin**
  - **Intent** — Trouver l’exercice correspondant à un nom courant ou à une clé Garmin et distinguer ses variantes.
  - **Succès** — « Squat à la barre » propose des références distinctes pour le squat arrière et le squat avant. Une référence exacte identifie un seul exercice. Une référence inconnue ne déclenche aucune création.
- **CAP-2 · Prévisualiser la séance**
  - **Intent** — Définir les exercices dans l’ordre souhaité, avec les répétitions ou la durée et la charge de chaque série. Régler séparément le repos entre séries et la transition vers l’exercice suivant.
  - **Succès** — L’aperçu présente deux séries de huit squats à 60 kg, une série de six à 65 kg et 2 minutes avant les pompes, distinctes des 90 secondes entre les séries de squat. Il indique l’absence de charge externe pour les pompes et n’écrit rien dans la bibliothèque.
- **CAP-3 · Enregistrer et vérifier la séance**
  - **Intent** — Enregistrer la séance confirmée et retrouver son contenu par son identifiant Garmin.
  - **Succès** — La relecture confirme le sport et la séquence réelle des séries et des repos avec leurs valeurs. Le même appel confirmé renvoie le même ID. Une différence, même limitée à une transition, produit un conflit explicite sous le même nom.

## Contraintes

- Réutiliser l’accès réservé au propriétaire, l’activation d’écriture et la session Garmin Connect déjà utilisée pour créer des entraînements.
- Vérifier les références dans le catalogue Garmin. Présenter les variantes ambiguës et attendre le choix. Toute charge vient de la demande validée.
- Préserver les contrats des outils existants et le plafond de 200 outils du projet. Les bornes d’entrée et les résultats de vérification figurent dans le [contrat](contrat-musculation.md).

## Hors périmètre

- Modification des étapes d’une séance existante, correction d’une activité déjà enregistrée, circuits multi-exercices, supersets et progression automatique.
- Planification et envoi automatique à la montre. L’affichage sur un appareil reste un test distinct qui dépend de sa compatibilité.

## Critère de réussite

Sur le compte propriétaire, prévisualiser une séance de test, la confirmer, la retrouver comme séance de musculation et vérifier chaque étape structurée. Rejouer la création, obtenir le même ID et vérifier que la bibliothèque ne contient qu’une séance de ce nom.

## Hypothèses

- Le premier lot propose deux outils typés avec une recherche par clés Garmin et un ensemble limité d’alias français. Un bloc regroupe les séries identiques. Plusieurs blocs du même exercice décrivent les variations série par série. Les charges sont saisies en kg et les repos omis valent zéro.
