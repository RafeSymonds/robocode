"""Pull the two scores out of a Robocode results file and print them as a percentage.

Rows look like:  1st: rafe.Kestrel 1.0*<TAB>338 (94%)<TAB>100<TAB>...
A robot's displayed name can contain spaces (its version), so the row has to be
split on tabs rather than on whitespace.
"""
import re
import sys

path, mine, opponent = sys.argv[1:4]
mine = mine.rstrip("*")
opponent = opponent.rstrip("*")

scores = {}
for row in open(path):
    fields = row.rstrip("\n").split("\t")
    if len(fields) < 2:
        continue
    name = re.match(r"\s*\d+(?:st|nd|rd|th):\s*(.+?)\s*$", fields[0])
    score = re.match(r"\s*(\d+)", fields[1])
    if name and score:
        scores[name.group(1)] = int(score.group(1))


def find(key):
    for name, score in scores.items():
        if name == key or name.startswith(key + " "):
            return score
    return 0


mine_score, their_score = find(mine), find(opponent)
total = mine_score + their_score
print(f"{100.0 * mine_score / total if total else 0:.2f} {mine_score} {their_score}")
