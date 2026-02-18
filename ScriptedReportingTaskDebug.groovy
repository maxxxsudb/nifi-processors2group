// ==========================================================================
// НАСТРОЙКИ
// ==========================================================================
def TAG  = "DEBUG_NIFI"

// EXPORT | DUMP_ALL_GROUPS | DUMP_ROOT_CHILDREN
def MODE = "EXPORT"

def TARGET_GROUPS = ["Data Ingest", "Kafka Processing", "[BUS]_diss_groups"]

// Ограничение размера вывода Query (чтобы не рвать логи):
// 0 = без ограничений, иначе печатаем Query чанками по N id на строку
def MAX_IDS_PER_QUERY_LINE = 200

// ==========================================================================
// ВСПОМОГАТЕЛЬНОЕ ЛОГИРОВАНИЕ (ТОЛЬКО println)
// ==========================================================================
def q = { Object v ->
    if (v == null) return "null"
    def s = v.toString()
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    return "'${s}'"
}

def logLine = { String level, String type, Map fields = [:] ->
    def parts = ["${TAG}", "Level=${level}", "Type=${type}"]
    fields.each { k, v -> parts << "${k}=${q(v)}" }
    println parts.join(" ")
}

// Безопасно достать property/геттер (чтобы не падать на несовместимых DTO)
def safeProp = { obj, String prop ->
    try {
        return obj?."$prop"
    } catch (Throwable ignored) {
        return null
    }
}

def asList = { x ->
    if (x == null) return []
    if (x instanceof Collection) return x as List
    return [x]
}

// Достать детей-группы максимально совместимо
def getChildGroups = { groupStatus ->
    def candidates = ["processGroupStatus", "processGroupStatusList", "processGroups"]
    for (c in candidates) {
        def v = safeProp(groupStatus, c)
        if (v != null) return asList(v)
    }
    try {
        def m = groupStatus?.getClass()?.methods?.find { it.name == "getProcessGroupStatus" && it.parameterCount == 0 }
        if (m != null) return asList(m.invoke(groupStatus))
    } catch (Throwable ignored) { }
    return []
}

// Достать процессоры максимально совместимо
def getProcessors = { groupStatus ->
    def candidates = ["processorStatus", "processorStatusList", "processors"]
    for (c in candidates) {
        def v = safeProp(groupStatus, c)
        if (v != null) return asList(v)
    }
    try {
        def m = groupStatus?.getClass()?.methods?.find { it.name == "getProcessorStatus" && it.parameterCount == 0 }
        if (m != null) return asList(m.invoke(groupStatus))
    } catch (Throwable ignored) { }
    return []
}

// Нормализация имени (лечит NBSP и хвостовые пробелы)
def normalizeName = { String s ->
    if (s == null) return null
    s.replace('\u00A0', ' ')
     .replace('\u202F', ' ')
     .trim()
}

// Универсальный обход групп
def walkGroups
walkGroups = { groupStatus, String parentId, int depth, Closure visitor ->
    if (groupStatus == null) return
    visitor(groupStatus, parentId, depth)
    getChildGroups(groupStatus).each { child ->
        walkGroups(child, safeProp(groupStatus, "id") as String, depth + 1, visitor)
    }
}

// Сбор всех processorId внутри группы + всех её детей
def collectAllProcessorIds
collectAllProcessorIds = { groupStatus ->
    def ids = []

    getProcessors(groupStatus).each { ps ->
        def pid = safeProp(ps, "id")
        if (pid != null) ids << pid.toString()
    }

    getChildGroups(groupStatus).each { child ->
        ids.addAll(collectAllProcessorIds(child))
    }

    return ids
}

// ==========================================================================
// ОСНОВНОЙ КОД
// ==========================================================================
try {
    logLine("INFO", "start", [Mode: MODE, TargetGroups: TARGET_GROUPS])

    // --- Self-check: context/eventAccess ---
    def eventAccess = null
    try {
        eventAccess = context?.getEventAccess()
    } catch (Throwable t) {
        logLine("ERROR", "Init", [Msg: "context.getEventAccess() failed", Err: t.class.name, Detail: t.message])
        return
    }
    if (eventAccess == null) {
        logLine("ERROR", "Init", [Msg: "eventAccess is null"])
        return
    }

    // --- Получаем root ---
    def rootGroupStatus = null
    def rootHow = null

    try {
        def cs = eventAccess.getControllerStatus()
        rootGroupStatus = cs?.getProcessGroupStatus()
        rootHow = "controllerStatus.processGroupStatus"
    } catch (Throwable ignored) {
        // ignore
    }

    if (rootGroupStatus == null) {
        try {
            rootGroupStatus = eventAccess.getGroupStatus("root")
            rootHow = "getGroupStatus('root')"
        } catch (Throwable t2) {
            logLine("ERROR", "Init", [
                Msg: "Failed to obtain root group status",
                Err: t2.class.name,
                Detail: t2.message
            ])
            rootGroupStatus = null
        }
    }

    if (rootGroupStatus == null) {
        logLine("ERROR", "Init", [Msg: "rootGroupStatus is null (cannot proceed)"])
        return
    }

    logLine("INFO", "root_obtained", [
        How: rootHow,
        RootId: safeProp(rootGroupStatus, "id"),
        RootName: safeProp(rootGroupStatus, "name"),
        RootClass: rootGroupStatus.getClass().name
    ])

    def rootChildren = getChildGroups(rootGroupStatus)
    logLine("INFO", "root_children", [Count: rootChildren.size()])

    // ======================================================================
    // MODE: DUMP_ROOT_CHILDREN
    // ======================================================================
    if (MODE == "DUMP_ROOT_CHILDREN") {
        rootChildren.each { g ->
            def n = safeProp(g, "name") as String
            logLine("INFO", "PG", [
                Name: n,
                Norm: normalizeName(n),
                Len: n?.length(),
                GroupId: safeProp(g, "id"),
                ParentId: safeProp(rootGroupStatus, "id"),
                Depth: 1
            ])
        }
        logLine("INFO", "complete", [Mode: MODE])
        return
    }

    // ======================================================================
    // MODE: DUMP_ALL_GROUPS
    // ======================================================================
    if (MODE == "DUMP_ALL_GROUPS") {
        def count = 0
        walkGroups(rootGroupStatus, null, 0) { g, parentId, depth ->
            def n = safeProp(g, "name") as String
            logLine("INFO", "PG", [
                Name: n,
                Norm: normalizeName(n),
                Len: n?.length(),
                GroupId: safeProp(g, "id"),
                ParentId: parentId,
                Depth: depth
            ])
            count++
        }
        logLine("INFO", "dump_complete", [Count: count])
        return
    }

    // ======================================================================
    // MODE: EXPORT
    // ======================================================================
    // 1) Индексируем все группы по нормализованному имени
    def groupsByNormName = [:].withDefault { [] }
    def indexed = 0

    walkGroups(rootGroupStatus, null, 0) { g, parentId, depth ->
        def n = safeProp(g, "name") as String
        groupsByNormName[normalizeName(n)] << g
        indexed++
    }

    logLine("INFO", "indexed", [Groups: indexed, UniqueNames: groupsByNormName.size()])

    // 2) Экспортируем только целевые
    def foundTargets = [] as Set

    TARGET_GROUPS.each { target ->
        def normTarget = normalizeName(target)
        def matches = groupsByNormName[normTarget] ?: []

        if (matches.isEmpty()) {
            logLine("WARN", "NotFound", [Group: target, Norm: normTarget])
            return
        }

        foundTargets.add(target)

        if (matches.size() > 1) {
            logLine("WARN", "DuplicateTargetName", [
                Group: target,
                MatchCount: matches.size(),
                GroupIds: matches.collect { safeProp(it, "id") }.join(",")
            ])
        }

        matches.each { grp ->
            def grpName = safeProp(grp, "name")
            def grpId   = safeProp(grp, "id")

            def allIds = collectAllProcessorIds(grp)
            if (allIds.isEmpty()) {
                logLine("INFO", "Export", [Group: target, ActualName: grpName, GroupId: grpId, Result: "Empty"])
                return
            }

            if (MAX_IDS_PER_QUERY_LINE != null && MAX_IDS_PER_QUERY_LINE > 0 && allIds.size() > MAX_IDS_PER_QUERY_LINE) {
                def chunks = allIds.collate(MAX_IDS_PER_QUERY_LINE)
                def total = chunks.size()
                chunks.eachWithIndex { chunk, idx ->
                    def queryPart = "(" + chunk.join(" OR ") + ")"
                    logLine("INFO", "Export", [
                        Group: target, ActualName: grpName, GroupId: grpId,
                        ProcessorCount: allIds.size(),
                        Chunk: "${idx + 1}/${total}",
                        Query: queryPart
                    ])
                }
            } else {
                def queryPart = "(" + allIds.join(" OR ") + ")"
                logLine("INFO", "Export", [
                    Group: target, ActualName: grpName, GroupId: grpId,
                    ProcessorCount: allIds.size(),
                    Query: queryPart
                ])
            }
        }
    }

    logLine("INFO", "complete", [Mode: MODE, Found: foundTargets.size(), Total: TARGET_GROUPS.size()])

} catch (Throwable t) {
    // Главное: чтобы ошибка была В stdout и попала в Grafana
    logLine("ERROR", "Exception", [Class: t.class.name, Msg: t.message])
    t.stackTrace.take(25).eachWithIndex { st, i ->
        logLine("ERROR", "ExceptionStack", [Index: i, At: st.toString()])
    }
}
