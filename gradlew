#!/bin/sh
GRADLE_OPTS="${GRADLE_OPTS:-"-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"}"
exec gradle "$@"
