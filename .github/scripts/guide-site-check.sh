#!/usr/bin/env bash
# Checks the guide website build (.github/workflows/guide-site.yml): the export finished, its game log has no error
# the allow-list does not name, and the site has a page for every guide page.
set -euo pipefail
log=runs/storage-neoforge/exportGuide/logs/latest.log
export_dir=storage/neoforge/build/exportedGuide
site=storage/neoforge/build/guideWebsite
guide=storage/common/src/main/resources/assets/maxtech_storage/guides/maxtech_storage/guide
allow=.github/scripts/guide-site-log-allow.txt
status=0

if [ ! -f "$export_dir/index.json" ]; then
    echo "::error::The guide export wrote no $export_dir/index.json"
    status=1
fi

if [ ! -f "$log" ]; then
    echo "::error::No export log at $log"
    status=1
else
    # Log4j's dev format: [time] [thread/LEVEL] [logger/]: message. Allow-list lines are extended regexes.
    patterns=$(mktemp)
    grep -vE '^[[:space:]]*(#|$)' "$allow" > "$patterns" || true
    if [ -s "$patterns" ]; then
        errors=$(grep -E '/(ERROR|FATAL)\]' "$log" | grep -vEf "$patterns" || true)
    else
        errors=$(grep -E '/(ERROR|FATAL)\]' "$log" || true)
    fi
    rm -f "$patterns"
    if [ -n "$errors" ]; then
        echo "::error::The export log has errors the allow-list ($allow) does not name:"
        echo "$errors"
        status=1
    fi
fi

# GuideME's WebPageCompiler writes one <page id>.html per guide page; its assets are no HTML.
expected=$(find "$guide" -name '*.md' | wc -l)
pages=0
if [ -d "$site" ]; then
    pages=$(find "$site" -name '*.html' | wc -l)
fi
echo "Guide pages: $expected, site pages: $pages"
if [ "$pages" -lt "$expected" ]; then
    echo "::error::The site has $pages pages, fewer than the guide's $expected"
    status=1
fi
exit "$status"
