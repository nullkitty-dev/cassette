# Benchmark corpus

Real-world CSS, not synthetic.
Sourced only from permissively-licensed projects, matching the planned Apache-2.0 release.

| file                           | version | bytes     | license           | in git |
|--------------------------------|---------|-----------|-------------------|--------|
| `small-handwritten.css`        | n/a     | 3,604     | this project      | yes    |
| `medium-bootstrap.css`         | 5.3.3   | 281,046   | MIT, getbootstrap | no     |
| `large-generated-tailwind.css` | 2.2.19  | 3,642,321 | MIT, tailwindcss  | no     |

The two large files are **fetched, not committed**: `.gitignore` excludes them, and only their `*.LICENSE` files and the versions pinned above live in git.
`Corpus.isAvailable()` reports whether an entry is present, so the suite runs against whatever has been fetched.

Between them they cover three shapes that stress different things.
The small file is latency-dominated and deliberately messy.
Bootstrap is authored CSS: deep selectors, long value lists, comments.
Tailwind is generated CSS: 176,000 lines of short near-identical rules, thousands of escaped class selectors (`.sm\:w-1\/2`), and the highest string duplication of the three.

## Fetching the missing two

```sh
cd src/jmh/resources/corpus

curl -L -o medium-bootstrap.css \
  https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.css
curl -L -o medium-bootstrap.LICENSE \
  https://raw.githubusercontent.com/twbs/bootstrap/v5.3.3/LICENSE

curl -L -o large-generated-tailwind.css \
  https://cdn.jsdelivr.net/npm/tailwindcss@2.2.19/dist/tailwind.css
curl -L -o large-generated-tailwind.LICENSE \
  https://raw.githubusercontent.com/tailwindlabs/tailwindcss/v2.2.19/LICENSE
```

**The Tailwind entry is a pinned dist build, not local CLI output.**
The plan was `npx tailwindcss -i input.css -o large-generated-tailwind.css`, and it was dropped for the reason this file already warned about: CLI output is generation-config dependent, and in Tailwind 4 it is _content_-dependent as well, since the CLI emits only the utilities it finds used in the files it scans, so with no project to scan it produces a few KB of preflight and nothing else.
A pinned dist build is the same kind of artifact, has every utility in it by construction, and is reproducible from a URL rather than from a config nobody kept.

2.2.19 is the last release that shipped an all-utilities dist.
Pin any replacement in the table above, and record what produced it.

## Keeping them honest

Both files parse with **zero diagnostics** and serialize idempotently in every output mode.
That is worth re-checking whenever one is replaced: a corpus entry that provokes a diagnostic is either a parser bug or a file that is not the well-formed real-world CSS these are here to represent, and either way it wants finding before a benchmark is run over it.
