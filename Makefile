# The documentation gate, and CI runs exactly this target.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal state
# of affairs, and then neither is read.
#
# NOT docs-bootstrap's own Makefile, which is parameterised for its `example/` tree: copied verbatim
# it prints "no docs tree - nothing checked" and goes green having checked nothing. A gate that
# cannot find its subject must say so, which is what `guard` below is.
#
# The scripts in scripts/ are copies from docs-bootstrap at 835350b. Before replacing them with a
# "newer" copy, diff against the docs-bootstrap repository itself: a plugin cache under the same
# version number has been seen to be older than a project's copy.

PY ?= python3

.PHONY: check gate guard report fix help

help:
	@echo "make check   - the gate: blocking checks, exactly what CI runs"
	@echo "make report  - non-blocking: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index and the coverage map"

check: gate report

# Blocking. A failure here means the documentation contradicts itself.
gate: guard
	$(PY) scripts/backlog_index.py --check
	$(PY) scripts/docs_check.py
	$(PY) scripts/coverage_map.py --check

# The subject has to exist before any verdict about it means anything.
guard:
	@test -d docs || { echo "no docs/ tree - the gate has no subject"; exit 1; }
	@test -f docs/research/research-architecture.md || { echo "no research document - the gate has no entry point"; exit 1; }
	@n=$$(ls docs/backlog/B-*.md 2>/dev/null | wc -l); \
	  test "$$n" -gt 0 || { echo "no backlog items - the index check would pass vacuously"; exit 1; }; \
	  echo "guard: docs/ present, $$n backlog items"

# Non-blocking, read by a person. Nothing is implemented yet, so every anchor is NOT FOUND until the
# code lands; that is the one legitimate exception, and it ends item by item.
report:
	$(PY) scripts/bdd_report.py
	$(PY) scripts/code_anchors.py --repos ..

fix:
	$(PY) scripts/backlog_index.py
	$(PY) scripts/coverage_map.py --fix
