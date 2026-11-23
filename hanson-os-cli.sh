#!/system/bin/sh
# ==============================================================================
#  hanson — Native Android Mobile-Use OS CLI (UID 2000 / shell)
#  Installed at: /data/local/tmp/hanson
#  Architecture: Direct Binder / InputManager / ActivityTaskManager / Package /
#                Settings / ContentProvider / Artemis(:18888) Hybrid OS CLI
# ==============================================================================

VERSION="1.3.0-os-cli"
A11Y_PORT=18888

cmd="$1"
shift 2>/dev/null

json_escape() {
    printf '%s' "$1" | tr '\n\r\t' '   ' | sed 's/\\/\\\\/g; s/"/\\"/g'
}

append_dedupe_service() {
    EXISTING="$1"
    TARGET="$2"
    if [ -z "$EXISTING" ] || [ "$EXISTING" = "null" ]; then
        printf '%s' "$TARGET"
        return
    fi
    RESULT=""
    FOUND=0
    OLD_IFS="$IFS"
    IFS=":"
    for ENTRY in $EXISTING; do
        [ -z "$ENTRY" ] && continue
        [ "$ENTRY" = "null" ] && continue
        if [ "$ENTRY" = "$TARGET" ]; then
            FOUND=1
        fi
        case ":$RESULT:" in
            *":$ENTRY:"*) ;;
            *)
                if [ -z "$RESULT" ]; then
                    RESULT="$ENTRY"
                else
                    RESULT="$RESULT:$ENTRY"
                fi
                ;;
        esac
    done
    IFS="$OLD_IFS"
    if [ "$FOUND" -eq 0 ]; then
        if [ -z "$RESULT" ]; then
            RESULT="$TARGET"
        else
            RESULT="$RESULT:$TARGET"
        fi
    fi
    printf '%s' "$RESULT"
}

resolve_hanson_packages() {
    EXPLICIT="$1"
    if [ "$EXPLICIT" = "com.hanson.mobileuse" ] || [ "$EXPLICIT" = "com.hanson.mobileuse.debug" ]; then
        printf '%s' "$EXPLICIT"
        return
    fi
    if [ "$HANSON_PACKAGE" = "com.hanson.mobileuse" ] || [ "$HANSON_PACKAGE" = "com.hanson.mobileuse.debug" ]; then
        printf '%s' "$HANSON_PACKAGE"
        return
    fi
    FOUND_PKGS=""
    for CAND in com.hanson.mobileuse.debug com.hanson.mobileuse; do
        if pm list packages "$CAND" 2>/dev/null | grep -q "^package:$CAND\$"; then
            if [ -z "$FOUND_PKGS" ]; then
                FOUND_PKGS="$CAND"
            else
                FOUND_PKGS="$FOUND_PKGS $CAND"
            fi
        fi
    done
    if [ -z "$FOUND_PKGS" ]; then
        printf '%s' "com.hanson.mobileuse"
    else
        printf '%s' "$FOUND_PKGS"
    fi
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
            if [ -n "$ARTEMIS_TOKEN" ]; then
                curl -s -H "X-Artemis-Token: $ARTEMIS_TOKEN" "http://127.0.0.1:$A11Y_PORT/elements"
            else
                curl -s "http://127.0.0.1:$A11Y_PORT/elements"
            fi
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
            echo "{\"status\":\"ok\",\"action\":\"settings_put\",\"namespace\":\"$NS\",\"key\":\"$KEY\",\"value\":\"$(json_escape "$VAL")\"}"
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

    uarp|reverse)
        PKG="$1"
        echo "=================================================================="
        echo " [UARP] Universal App Reverse-Engineering Protocol: $PKG"
        echo "=================================================================="
        APK_PATH=$(pm path "$PKG" 2>/dev/null | head -n 1 | sed 's/package://g')
        echo " [APK Path] $APK_PATH"
        echo "--- [1. Exported Activities & Entry Points] ---"
        dumpsys package "$PKG" 2>/dev/null | grep -E " filter |Activity|Action: \"|Category: \"" | head -n 35
        echo "--- [2. Custom Deep-Link URI Schemes & Hosts] ---"
        dumpsys package "$PKG" 2>/dev/null | grep -E "Scheme: \"|Authority: \"|Path: \"" | head -n 35
        echo "--- [3. Exported ContentProviders] ---"
        dumpsys package "$PKG" 2>/dev/null | grep -E "Provider\{|authority=" | head -n 15
        ;;

    self-heal)
        # Re-grants privileged permissions, AppOps, Accessibility (append+dedupe) & Notification listeners from UID 2000
        PKGS=$(resolve_hanson_packages "$1")
        MERGED=$(settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')
        for PKG in $PKGS; do
            appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
            appops set "$PKG" WRITE_SETTINGS allow 2>/dev/null
            appops set "$PKG" GET_USAGE_STATS allow 2>/dev/null
            appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
            pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
            pm grant "$PKG" android.permission.DUMP 2>/dev/null
            pm grant "$PKG" android.permission.READ_LOGS 2>/dev/null
            MERGED=$(append_dedupe_service "$MERGED" "$PKG/com.hanson.mobileuse.accessibility.HansonAccessibilityService")
            cmd notification allow_listener "$PKG/com.hanson.mobileuse.notification.HansonNotificationListenerService" 2>/dev/null
            dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1
        done
        settings put secure enabled_accessibility_services "$MERGED"
        settings put secure accessibility_enabled 1
        echo "{\"status\":\"ok\",\"action\":\"self_heal_complete\",\"packages\":\"$(json_escape "$PKGS")\"}"
        ;;

    sh|exec|eval|bash)
        echo "{\"status\":\"error\",\"error\":\"Security rejection: arbitrary sh -c execution is disabled (use structured hanson subcommands)\"}"
        exit 126
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
        echo "   hanson self-heal [package]              Auto-restore permissions & A11y/Notif bindings (append+dedupe)"
        echo "=================================================================="
        ;;
esac
