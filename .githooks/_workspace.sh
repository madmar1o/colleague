#!/bin/sh
# _workspace.sh — общий помощник git-хуков этого репозитория.
# Не является хуком: git выполняет только файлы с именами git-хуков.
#
# Назначение: найти рабочую папку (родительскую для корня репозитория) и,
# если она рядом, записать в неё событие git и асинхронно запустить
# фоновый пересчёт SonarQube. В свежем клоне вне рабочей папки обе функции
# молча ничего не делают, чтобы хуки не ломали коммит.

# Каталог репозитория (для linked worktree — корень основного репозитория).
repo_dir() {
	common=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)
	if [ -z "$common" ]; then
		common=$(git rev-parse --git-common-dir 2>/dev/null) || return 1
		top=$(git rev-parse --show-toplevel 2>/dev/null) || return 1
		case "$common" in
			/*) ;;
			*) common="$top/$common" ;;
		esac
	fi
	( cd "$common" && pwd ) | sed 's|/\.git$||'
}

# Рабочая папка: родитель корня репозитория; COLLEAGUE_WS_ROOT перекрывает поиск.
ws_root() {
	if [ -n "$COLLEAGUE_WS_ROOT" ]; then
		printf '%s' "$COLLEAGUE_WS_ROOT"
		return 0
	fi
	repo=$(repo_dir) || return 1
	printf '%s' "$(dirname "$repo")"
}

# Событие git в notes/git-events.log рабочей папки (если она есть).
git_event() {
	name=$1
	shift
	ws=$(ws_root) || return 0
	log="$ws/notes/git-events.log"
	[ -d "$ws/notes" ] || return 0
	branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
	head=$(git rev-parse --short HEAD 2>/dev/null)
	printf '%s event=%s branch=%s head=%s %s\n' \
		"$(date -Iseconds)" "$name" "$branch" "$head" "$*" >> "$log"
}

# Фоновый запуск sonar_track.ps1 из tools/ рабочей папки (если он там есть).
sonar_track_async() {
	ws=$(ws_root) || return 0
	script="$ws/tools/sonar_track.ps1"
	[ -f "$script" ] || return 0
	if command -v powershell.exe >/dev/null 2>&1; then
		powershell.exe -WindowStyle Hidden -NoProfile -ExecutionPolicy Bypass \
			-File "$script" >/dev/null 2>&1 &
	elif command -v pwsh >/dev/null 2>&1; then
		pwsh -NoProfile -ExecutionPolicy Bypass -File "$script" >/dev/null 2>&1 &
	fi
	return 0
}
