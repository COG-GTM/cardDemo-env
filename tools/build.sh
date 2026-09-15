#!/bin/sh
set -u

mkdir -p work loadlib
required_status=0
for source in cobol/*; do
    [ -f "$source" ] || continue
    name=$(basename "$source")
    stem=${name%.*}
    case "$stem" in
        CO*)
            echo "$stem: online, not built"
            continue
            ;;
    esac
    input="$source"
    if grep -q 'EXEC SQL' "$source"; then
        input="work/$stem.cob"
        echo "$stem: precompiling with ocesql"
        if ! ocesql "$source" "$input"; then
            echo "$stem: excluded (Open COBOL ESQL failed)"
            case "$stem" in
                CBXFR01C|XFERFEE|CBXFR03C) required_status=1 ;;
            esac
            continue
        fi
    fi
    echo "$stem: building"
    if cobc -m -std=ibm -I copybook -o "loadlib/$stem.so" "$input" \
        $(grep -q 'EXEC SQL' "$source" && printf '%s' '-locesql' || true); then
        continue
    fi
    echo "$stem: retrying with -std=default"
    if cobc -m -std=default -I copybook -o "loadlib/$stem.so" "$input" \
        $(grep -q 'EXEC SQL' "$source" && printf '%s' '-locesql' || true); then
        continue
    fi
    echo "$stem: excluded (does not compile with GnuCOBOL)"
    case "$stem" in
        CBXFR01C|XFERFEE|CBXFR03C) required_status=1 ;;
    esac
done
exit "$required_status"
