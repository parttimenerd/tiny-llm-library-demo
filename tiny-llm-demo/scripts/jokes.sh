#!/usr/bin/env bash
# jokes.sh — Pure-bash joke generator (no LLM, no network)
# Usage: ./jokes.sh
set -euo pipefail

# --- Fragment banks -------------------------------------------------------
# Convention: no apostrophes in entries (keeps single-quoting safe), and
# entries stay lowercase unless they start a sentence in their template.

SUBJECTS=(
  'a null pointer'
  'the garbage collector'
  'a recursive function'
  'the regex'
  'an off-by-one error'
  'the Maven build'
  'a segfault'
  'the Java developer'
  'a lint warning'
  'the production database'
  'a floating-point number'
  'the CI pipeline'
)

SUBJECTS_PLURAL=(
  'Java developers'
  'DevOps engineers'
  'regexes'
  'threads'
  'unit tests'
  'microservices'
  'AI agents'
  'product managers'
  'stack frames'
  'code reviewers'
)

ACTIONS=(
  'cross the road'
  'refuse to compile'
  'go to therapy'
  'file a bug report against itself'
  'timeout during the keynote'
  'walk away from the keyboard'
  'open a pull request at 3 AM'
  'press Escape'
  'migrate to the cloud'
  'ask for a rewrite in Rust'
)

PLACES=(
  'a bar'
  'a stand-up meeting'
  'production'
  'a code review'
  'an infinite loop'
  'a null check'
)

OBJECTS=(
  'a byte'
  'another byte'
  'a strongly typed cocktail'
  '2 beers and a runtime exception'
  'an endless loop of peanuts'
  'a mocktail, strictly for testing'
  'a lambda on the rocks'
  'a shot of espresso with no side effects'
)

# Paired by index with PUNCHLINES_BOAST: ADJECTIVES[i] lands with
# PUNCHLINES_BOAST[i]. Keep both banks the same length and order-aligned.
ADJECTIVES=(
  'recursive'
  'over-engineered'
  'legacy'
  'asynchronous'
  'strongly typed'
  'distributed'
  'deprecated'
  'concurrent'
)

NOUNS=(
  'code'
  'regex'
  'startup script'
  'bash one-liner'
  'test suite'
  'commit message'
  'side project'
)

# Subject-free on purpose: must fit "Why did <person|thing> ...? Because <x>."
PUNCHLINES=(
  'of too many dependencies'
  'the stack was about to overflow'
  'nobody dared to refactor the mess'
  'the other option had better documentation'
  'the cache was full of memories'
  'a deadline was closing in'
  'the release notes looked lonely'
  'a dangling pointer said follow me'
  'someone kept throwing exceptions'
  'the logs told a different story'
  'production was on fire again'
  'the tests only pass when nobody is watching'
)

PUNCHLINES_BARTENDER=(
  'We do not serve your type here'
  'This is some kind of merge conflict'
  'That order is going to need a mutex'
  'One of you gets garbage collected by closing time'
  'I am going to need to see some ID-empotency'
  'This reeks of a race condition'
  'Sorry, we are single-threaded tonight'
  'You three again? Third callback today'
)

PUNCHLINES_COUNT=(
  'None, that is a hardware problem'
  'Just one, but it needs 47 committee approvals'
  'Two, one to change it and one to deprecate the socket'
  'None, they declare the darkness a feature and ship it'
  'Three, counting the one who writes the postmortem'
  'Zero, dark mode is mandatory here'
  'One, but the build fails anyway'
  'Depends, have you tried turning the room off and on again'
  'Five, one to change it and four to say it worked on their machine'
  'None, the bulb is out of scope this sprint'
  'Just one, but first there is a three-hour meeting about the bulb'
)

PUNCHLINES_BOAST=(
  'it calls itself to finish the story'
  'it comes with its own framework and two podcasts'
  'debugging it requires an archaeologist'
  'the punchline arrives three releases later'
  'even its small talk has to pass the compiler'
  'it breaks in a data center you have never heard of'
  'somehow it still runs all of production'
  'it deadlocks when you compliment it'
)

# Paired banks must stay in sync (boast picks one index for both).
if ((${#ADJECTIVES[@]} != ${#PUNCHLINES_BOAST[@]})); then
  printf 'jokes.sh: ADJECTIVES and PUNCHLINES_BOAST are out of sync\n' >&2
  exit 1
fi

# --- Picker + joke templates ----------------------------------------------

# pick ELEMENT... — echo one random argument. Fails loudly on empty input
# (guards `set -u`; callers always pass a non-empty "${BANK[@]}").
pick() {
  if (($# == 0)); then
    printf 'pick: nothing to pick from\n' >&2
    return 1
  fi
  local -a items=("$@")
  local index=$((RANDOM % $#))
  printf '%s\n' "${items[index]}"
}

joke_qa() {
  local subject action punchline
  subject=$(pick "${SUBJECTS[@]}")
  action=$(pick "${ACTIONS[@]}")
  punchline=$(pick "${PUNCHLINES[@]}")
  printf 'Why did %s %s? Because %s.\n' "$subject" "$action" "$punchline"
}

joke_bar() {
  local subject place object
  subject=$(pick "${SUBJECTS[@]}")
  place=$(pick "${PLACES[@]}")
  object=$(pick "${OBJECTS[@]}")
  printf 'So %s walks into %s and orders %s.\n' "$subject" "$place" "$object"
}

joke_triple_bar() {
  local first second third punchline
  first=$(pick "${SUBJECTS[@]}")
  second=$(pick "${SUBJECTS[@]}")
  while [[ "$second" == "$first" ]]; do second=$(pick "${SUBJECTS[@]}"); done
  third=$(pick "${SUBJECTS[@]}")
  while [[ "$third" == "$first" || "$third" == "$second" ]]; do third=$(pick "${SUBJECTS[@]}"); done
  punchline=$(pick "${PUNCHLINES_BARTENDER[@]}")
  printf 'So %s, %s, and %s walk into a bar. The bartender says: %s.\n' \
    "$first" "$second" "$third" "$punchline"
}

joke_lightbulb() {
  local subjects punchline
  subjects=$(pick "${SUBJECTS_PLURAL[@]}")
  punchline=$(pick "${PUNCHLINES_COUNT[@]}")
  printf 'How many %s does it take to change a light bulb? %s.\n' "$subjects" "$punchline"
}

joke_boast() {
  # One index for both paired banks, so adjective and punchline always agree.
  local index=$((RANDOM % ${#ADJECTIVES[@]}))
  local noun
  noun=$(pick "${NOUNS[@]}")
  printf 'My %s is so %s, %s.\n' "$noun" "${ADJECTIVES[index]}" "${PUNCHLINES_BOAST[index]}"
}

# --- Main -------------------------------------------------------------------

TEMPLATES=(joke_qa joke_bar joke_triple_bar joke_lightbulb joke_boast)

template=$(pick "${TEMPLATES[@]}")
"$template"
