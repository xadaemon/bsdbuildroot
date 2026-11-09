# Extracts termcap entries, and the entries they include via tc=, from a
# termcap(5) file. Keeps the original order and text of each entry.
#
#   awk -v want="xterm screen vt100" -f termcap-extract.awk termcap

function flush() {
	if (rec == "")
		return
	n++
	text[n] = rec
	head = rec
	sub(/:.*/, "", head)
	sub(/^[ \t]+/, "", head)
	cnt = split(head, names, "|")
	for (i = 1; i <= cnt; i++)
		byname[names[i]] = n
	joined = rec
	gsub(/\\\n[ \t]*/, "", joined)
	deps[n] = ""
	while (match(joined, /:tc=[^:]*/)) {
		deps[n] = deps[n] " " substr(joined, RSTART + 4, RLENGTH - 4)
		joined = substr(joined, RSTART + RLENGTH)
	}
	rec = ""
}

{
	if (rec == "" && ($0 ~ /^#/ || $0 ~ /^[ \t]*$/))
		next
	rec = rec $0 "\n"
	if ($0 !~ /\\$/)
		flush()
}

END {
	flush()
	cnt = split(want, queue, " ")
	head_ = 1
	while (head_ <= cnt) {
		name = queue[head_++]
		if (!(name in byname)) {
			print "termcap-extract: no entry for " name > "/dev/stderr"
			exit 1
		}
		idx = byname[name]
		if (idx in picked)
			continue
		picked[idx] = 1
		dc = split(deps[idx], d, " ")
		for (j = 1; j <= dc; j++)
			queue[++cnt] = d[j]
	}
	for (i = 1; i <= n; i++)
		if (i in picked)
			printf "%s", text[i]
}
