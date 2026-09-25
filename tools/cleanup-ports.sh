#!/usr/bin/env bash
#
# tools/cleanup-ports.sh
#
# 杀占用指定端口的进程 — 给 Maven Surefire fork 残留 + mvn exec:java 派生子 JVM
# 兜底用。
#
# 为什么需要：
#   - Maven Surefire forkMode = once/always 时，单测 @AfterEach 抛异常 / SIGKILL
#     会让 A2aServer.stop() 不执行，HttpServer 继续 listen 8080/9090。
#   - `mvn exec:java` / `mvn spring-boot:run` 派生的子 JVM 经常吞 SIGINT，
#     父进程退后子进程留。
#   - OS 不会主动回收 listen socket，新一轮测试同端口起不来。
#
# 用法：
#   tools/cleanup-ports.sh                  # 杀默认端口 8080, 9090
#   tools/cleanup-ports.sh 7001 50051       # 追加端口
#   tools/cleanup-ports.sh -n 8080 9090     # dry-run，只打印不杀
#   tools/cleanup-ports.sh -a               # 杀全部 Java 进程（慎用！）
#
# 行为约定：
#   - 只杀 LISTEN 状态的进程，不动 ESTABLISHED 连接（避免打断正在跑的 curl/压测）。
#   - 默认只杀 Java 进程（命令行包含 java），不杀系统进程。
#   - 先 SIGTERM 给 2 秒机会，顽强分子 SIGKILL。
#   - 退出码：0 = 全部清理，1 = 部分端口无进程可杀（不算错），2 = 参数错误。
#
# 不引入依赖：lsof + awk + grep + kill，全 macOS / Linux 自带。

set -u

DRY_RUN=0
KILL_ALL=0
PORTS=()

for arg in "$@"; do
    case "$arg" in
        -h|--help)
            sed -n '3,30p' "$0"
            exit 0
            ;;
        -n|--dry-run)
            DRY_RUN=1
            ;;
        -a|--all-java)
            KILL_ALL=1
            ;;
        -*)
            echo "unknown flag: $arg" >&2
            exit 2
            ;;
        *)
            PORTS+=("$arg")
            ;;
    esac
done

if [ "$KILL_ALL" = "1" ]; then
    if [ "$DRY_RUN" = "1" ]; then
        echo "[dry-run] would kill all java processes:"
        pgrep -fl '^java' || echo "  (none)"
        exit 0
    fi
    echo "killing all java processes..."
    pgrep -f '^java' | xargs -r kill -TERM 2>/dev/null
    sleep 2
    pgrep -f '^java' | xargs -r kill -KILL 2>/dev/null
    echo "done."
    exit 0
fi

# 默认端口：项目里 demos + A2aServer 默认
if [ ${#PORTS[@]} -eq 0 ]; then
    PORTS=(8080 9090)
fi

# 平台分流：macOS 用 lsof +nP，Linux 优先 ss（更快）回退 lsof。
OS=$(uname -s)

list_pids_on_port() {
    local port="$1"
    if [ "$OS" = "Darwin" ]; then
        # lsof -nP: 不解析主机名不解析端口名（输出更快、可读性更好）
        lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null
    else
        # Linux: ss 优先，ss -ltnp '( sport = :$port )'
        if command -v ss >/dev/null 2>&1; then
            ss -ltnp "( sport = :$port )" 2>/dev/null \
                | awk 'NR>1 {print $0}' \
                | grep -oP 'pid=\K[0-9]+' \
                | sort -u
        else
            lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null
        fi
    fi
}

any_killed=0
for port in "${PORTS[@]}"; do
    pids=$(list_pids_on_port "$port")
    if [ -z "$pids" ]; then
        echo "port $port: (no listener)"
        continue
    fi
    for pid in $pids; do
        # 守卫 1：非数字 PID 跳过（防止 lsof 异常输出）
        if ! [[ "$pid" =~ ^[0-9]+$ ]]; then
            continue
        fi
        # 守卫 2：命令行包含 java 才杀（避免误杀系统服务）
        cmdline=$(ps -p "$pid" -o command= 2>/dev/null || true)
        case "$cmdline" in
            *java*|*surefire*|*fork*)
                ;;
            *)
                echo "port $port: pid=$pid cmd='$cmdline' — not java, skip"
                continue
                ;;
        esac
        if [ "$DRY_RUN" = "1" ]; then
            echo "[dry-run] port $port: would kill pid=$pid cmd='$cmdline'"
            any_killed=1
            continue
        fi
        echo "port $port: killing pid=$pid cmd='$cmdline'"
        kill -TERM "$pid" 2>/dev/null || true
    done
    # 给 2 秒优雅退，再 SIGKILL
    if [ "$DRY_RUN" = "0" ]; then
        sleep 2
        for pid in $pids; do
            if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
                echo "port $port: SIGKILL pid=$pid (still alive after SIGTERM)"
                kill -KILL "$pid" 2>/dev/null || true
            fi
        done
        any_killed=1
    fi
done

if [ "$DRY_RUN" = "1" ] && [ "$any_killed" = "0" ]; then
    echo "[dry-run] no listeners on ports: ${PORTS[*]}"
fi

exit 0
