# Sourced by run-*.sh. Reads optional per-scenario settings:
#   parm       JCL PARM value (default 2022071800)
#   timestamp  frozen clock, ISO local date-time (default 2022-07-18T01:02:03.45)
#   missing    DD names whose dataset does not exist for this run
SCN_PARM="2022071800"; SCN_TIMESTAMP="2022-07-18T01:02:03.45"; SCN_MISSING=""
[ -f "$1/parm" ] && SCN_PARM="$(tr -d '\n' < "$1/parm")"
[ -f "$1/timestamp" ] && SCN_TIMESTAMP="$(tr -d '\n' < "$1/timestamp")"
[ -f "$1/missing" ] && SCN_MISSING="$(tr '\n' ' ' < "$1/missing")"
# GnuCOBOL's COB_CURRENT_DATE format: YYYY/MM/DD HH:MM:SS.hh
SCN_COB_DATE="$(echo "$SCN_TIMESTAMP" | sed -E 's/^([0-9]{4})-([0-9]{2})-([0-9]{2})T/\1\/\2\/\3 /')"
