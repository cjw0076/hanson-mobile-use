#!/system/bin/sh
# ==============================================================================
#  hanson — Native Android Mobile-Use OS CLI (UID 2000 / shell)
#  Installed at: /data/local/tmp/hanson
#  Architecture: Direct Binder / InputManager / ActivityTaskManager / Package /
#                Settings / ContentProvider / Artemis(:18888) Hybrid OS CLI
# ==============================================================================

VERSION="1.2.0-os-cli"
A11Y_PORT=18888

cmd="$1"
shift 2>/dev/null

json_escape() {
    printf '%s' "$1" | tr '\n\r\t' '   ' | sed 's/\\/\\\\/g; s/"/\\"/g'
}

case "$cmd" in
    status|info)
        ID_STR=$(id)
        SDK=$(getprop ro.build.version.sdk)
        MODEL=$(getprop ro.product.model)
        TOP_ACT=$(dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|ResumedActivity" | head -n 2 | tr '\n' ' ')
        WM_SIZE=$(wm size 2>/dev/null | tr '\n' ' ')
        BATT=$(dumpsys battery 2>/dev/null | grep -E "level:|temperature:" | tr '\n' ' ')
        echo "=================================================================="
        echo " Hanson Mobile-Use OS CLI v$VERSION (On-Device Kernel/Binder Harness)"
        echo "=================================================================="
        echo " [Identity]  $ID_STR"
        echo " [Device]    $MODEL (Android SDK $SDK) | $WM_SIZE"
        echo " [Battery]   $BATT"
        echo " [TopWindow] $TOP_ACT"
        echo "=================================================================="
        ;;

    top)
        echo "--- [WindowManager Focus] ---"
        dumpsys window displays 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -n 6
        echo "--- [Top Resumed Activity & View Hierarchy] ---"
        dumpsys activity top 2>/dev/null | grep -E "TASK|ACTIVITY|View Hierarchy:|mFocused" -A 2 | head -n 35
        ;;

    tree)
        echo "--- [UID-2000 Window State] ---"
        dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -n 4
        echo "--- [Artemis :18888 Real-Time Accessibility Nodes] ---"
        if command -v curl >/dev/null 2>&1; then
            curl -s "http://127.0.0.1:$A11Y_PORT/tree"
        else
            uiautomator dump /data/local/tmp/uidump.xml >/dev/null 2>&1
            cat /data/local/tmp/uidump.xml | head -c 2000
        fi
        echo ""
        ;;

    tap|click)
        X="${1:-540}"
        Y="${2:-1200}"
        input tap "$X" "$Y"
        echo "{\"status\":\"ok\",\"engine\":\"UID2000-input\",\"action\":\"tap\",\"x\":$X,\"y\":$Y}"
        ;;

    swipe)
        X1="${1:-540}"
        Y1="${2:-1600}"
        X2="${3:-540}"
        Y2="${4:-400}"
        DUR="${5:-250}"
        input swipe "$X1" "$Y1" "$X2" "$Y2" "$DUR"
        echo "{\"status\":\"ok\",\"engine\":\"UID2000-input\",\"action\":\"swipe\",\"from\":\"$X1,$Y1\",\"to\":\"$X2,$Y2\",\"ms\":$DUR}"
        ;;

    text|type)
        RAW="$*"
        ESCAPED=$(printf '%s' "$RAW" | sed 's/ /%s/g')
        input text "$ESCAPED"
        echo "{\"status\":\"ok\",\"engine\":\"UID2000-input\",\"action\":\"text\",\"value\":\"$(json_escape "$RAW")\"}"
        ;;

    key)
        KEY="${1:-KEYCODE_HOME}"
        input keyevent "$KEY"
        echo "{\"status\":\"ok\",\"engine\":\"UID2000-input\",\"action\":\"keyevent\",\"key\":\"$KEY\"}"
        ;;

    app)
        SUB="$1"
        PKG="$2"
        case "$SUB" in
            list)
                pm list packages -3 | sed 's/package://g' | sort
                ;;
            open|start)
                monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
                echo "{\"status\":\"ok\",\"action\":\"app_open\",\"package\":\"$PKG\"}"
                ;;
            stop|kill)
                am force-stop "$PKG"
                echo "{\"status\":\"ok\",\"action\":\"force_stop\",\"package\":\"$PKG\"}"
                ;;
            clear)
                pm clear "$PKG"
                ;;
            grant)
                PERM="$3"
                pm grant "$PKG" "$PERM"
                echo "{\"status\":\"ok\",\"action\":\"pm_grant\",\"package\":\"$PKG\",\"permission\":\"$PERM\"}"
                ;;
            appops)
                OP="$3"
                MODE="${4:-allow}"
                appops set "$PKG" "$OP" "$MODE"
                echo "{\"status\":\"ok\",\"action\":\"appops_set\",\"package\":\"$PKG\",\"op\":\"$OP\",\"mode\":\"$MODE\"}"
                ;;
            info)
                dumpsys package "$PKG" | grep -E "versionName|versionCode|userId|grantedPermissions" -A 10 | head -n 35
                ;;
            *)
                echo "Usage: hanson app [list|open|stop|clear|grant|appops|info] <package> [args...]"
                ;;
        esac
        ;;

    setting|settings)
        OP="${1:-get}"
        NS="${2:-system}"
        KEY="$3"
        shift 3 2>/dev/null
        VAL="$*"
        if [ "$OP" = "put" ]; then
            settings put "$NS" "$KEY" "$VAL"
            echo "{\"status\":\"ok\",\"action\":\"settings_put\",\"namespace\":\"$NS\",\"key\":\"$KEY\",\"value\":\"$VAL\"}"
        elif [ "$OP" = "list" ]; then
            settings list "$NS" | head -n 50
        else
            OUT=$(settings get "$NS" "$KEY")
            echo "{\"status\":\"ok\",\"namespace\":\"$NS\",\"key\":\"$KEY\",\"value\":\"$(json_escape "$OUT")\"}"
        fi
        ;;

    notif)
        SUB="${1:-list}"
        if [ "$SUB" = "expand" ]; then
            cmd statusbar expand-notifications
            echo "{\"status\":\"ok\",\"action\":\"expand_notifications\"}"
        elif [ "$SUB" = "collapse" ]; then
            cmd statusbar collapse
            echo "{\"status\":\"ok\",\"action\":\"collapse_panels\"}"
        else
            dumpsys notification --noredact 2>/dev/null | grep -E "NotificationRecord|pkg=|android.title=|android.text=" | head -n 40
        fi
        ;;

    provider)
        OP="${1:-query}"
        URI="${2:-content://settings/system}"
        content "$OP" --uri "$URI" | head -n 40
        ;;

    dump)
        SVC="${*:-activity activities}"
        dumpsys $SVC | head -n 60
        ;;

    self-heal)
        # Re-grants all privileged permissions, AppOps, Accessibility & Notification listeners from inside UID 2000!
        PKG="com.hanson.mobileuse"
        appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null
        appops set $PKG WRITE_SETTINGS allow 2>/dev/null
        appops set $PKG GET_USAGE_STATS allow 2>/dev/null
        appops set $PKG ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
        pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
        pm grant $PKG android.permission.DUMP 2>/dev/null
        pm grant $PKG android.permission.READ_LOGS 2>/dev/null
        settings put secure enabled_accessibility_services "$PKG/com.hanson.mobileuse.accessibility.HansonAccessibilityService"
        settings put secure accessibility_enabled 1
        cmd notification allow_listener "$PKG/com.hanson.mobileuse.notification.HansonNotificationListenerService" 2>/dev/null
        dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
        echo "{\"status\":\"ok\",\"action\":\"self_heal_complete\",\"package\":\"$PKG\"}"
        ;;

    sh|exec)
        sh -c "$*"
        ;;

    *)
        echo "=================================================================="
        echo " hanson — Mobile-Use Native OS CLI v$VERSION (UID 2000 / shell)"
        echo "=================================================================="
        echo " Commands:"
        echo "   hanson status                           UID/kernel groups, top window, battery"
        echo "   hanson top                              Inspect top Activity, Fragment & Window focus"
        echo "   hanson tree                             Dump hybrid Window + Accessibility UI tree"
        echo "   hanson tap <x> <y>                      Kernel/InputManager tap (bypasses FLAG_SECURE)"
        echo "   hanson swipe <x1> <y1> <x2> <y2> [ms]   Kernel/InputManager swipe"
        echo "   hanson text \"<string>\"                  Inject text input"
        echo "   hanson key <KEYCODE>                    Send hardware keyevent (HOME, BACK, 66, etc.)"
        echo "   hanson app [list|open|stop|grant|appops] Full PackageManager & AppOps control"
        echo "   hanson setting [get|put|list] <ns> ...  Direct system/secure/global OS settings"
        echo "   hanson notif [list|expand|collapse]     Direct StatusBar & NotificationRecord dump"
        echo "   hanson provider query <content://...>   Direct ContentProvider SQL query"
        echo "   hanson dump <service>                   Privileged dumpsys inspection"
        echo "   hanson self-heal                        Auto-restore all permissions & A11y/Notif bindings"
        echo "   hanson sh \"<pipeline>\"                  Execute raw UID-2000 Linux/Android shell command"
        echo "=================================================================="
        ;;
esac
