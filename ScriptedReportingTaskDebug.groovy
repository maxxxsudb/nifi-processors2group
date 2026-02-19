// ==========================================================================
// CONFIG
// ==========================================================================
def TAG  = "DEBUG_NIFI"
def MODE = "EXPORT"              // EXPORT | DUMP_ALL_GROUPS | DUMP_ROOT_CHILDREN

def TARGET_GROUPS = ["Data Ingest", "Kafka Processing", "[Bus]_Business_groups"]  // <-- исправьте на точное имя

// Если хотите матчить без учёта регистра: true
def CASE_INSENSITIVE = false

// ==========================================================================
// LOG HELPERS (println only)
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

def normalizeName = { String s ->
    if (s == null) return null
    def x = s.replace('\u00A0', ' ')
             .replace('\u202F', ' ')
             .trim()
    return CASE_INSENSITIVE ? x.toLowerCase(Locale.ROOT) : x
}

def isCollectionLike = { obj ->
    (obj instanceof Collection) || (obj != null && obj.getClass().isArray())
}

def asList = { obj ->
    if (obj == null) return []
    if (obj instanceof Collection) return obj as List
    if (obj.getClass().isArray()) return (obj as Object[]).toList()
    return [obj]
}

// ВАЖНО: не трогаем properties у List, иначе Groovy spread "поможет" и всё сломает.
def safeProp = { obj, String prop ->
    if (obj == null) return null
    if (isCollectionLike(obj)) return null
    try {
        return obj?."$prop"
    } catch (Throwable ignored) {
        return null
    }
}

def getChildGroups = { groupStatus ->
    def v = safeProp(groupStatus, "processGroupStatus")
    return asList(v)
}

def getProcessors = { groupStatus ->
    def v = safeProp(groupStatus, "processorStatus")
    return asList(v)
}

// ==========================================================================
// MAIN
// ==========================================================================
try {
    logLine("INFO", "Start", [Mode: MODE, TargetGroups: TARGET_GROUPS, CaseInsensitive: CASE_INSENSITIVE])

    def eventAccess = context?.getEventAccess()
    if (eventAccess == null) {
        logLine("ERROR", "Init", [Msg: "context.getEventAccess() returned null"])
        return
    }

    def root = null
    def rootHow = null

    try {
        root = eventAccess.getControllerStatus().getProcessGroupStatus()
        rootHow = "controllerStatus.processGroupStatus"
    } catch (Throwable t1) {
        try {
            root = eventAccess.getGroupStatus("root")
            rootHow = "getGroupStatus('root')"
        } catch (Throwable t2) {
            logLine("ERROR", "Init", [Msg: "Failed to obtain root", Err: t2.class.name, Detail: t2.message])
            return
        }
    }

    if (root == null) {
        logLine("ERROR", "Init", [Msg: "root is null"])
        return
    }

    // rootNodes: либо [root] если это объект, либо root как список если это ArrayList
    def rootNodes = isCollectionLike(root) ? asList(root) : [root]

    logLine("INFO", "Root", [
        How: rootHow,
        RootClass: root.getClass().name,
        RootIsList: isCollectionLike(root),
        RootNodes: rootNodes.size()
    ])

    // Walk: гарантируем что на вход всегда приходит НЕ список
    def walkGroups
    walkGroups = { groupStatus, String parentId, int depth, Closure visitor ->
        if (groupStatus == null) return

        // На всякий случай: если сюда всё же прилетел список — развернём
        if (isCollectionLike(groupStatus)) {
            asList(groupStatus).each { node -> walkGroups(node, parentId, depth, visitor) }
            return
        }

        visitor(groupStatus, parentId, depth)

        getChildGroups(groupStatus).each { child ->
            walkGroups(child, safeProp(groupStatus, "id") as String, depth + 1, visitor)
        }
    }

    def collectAllProcessorIds
    collectAllProcessorIds = { groupStatus ->
        def ids = []

        if (groupStatus == null) return ids
        if (isCollectionLike(groupStatus)) {
            asList(groupStatus).each { node -> ids.addAll(collectAllProcessorIds(node)) }
            return ids
        }

        getProcessors(groupStatus).each { ps ->
            def pid = safeProp(ps, "id")
            if (pid != null) ids << pid.toString()
        }

        getChildGroups(groupStatus).each { child ->
            ids.addAll(collectAllProcessorIds(child))
        }
        return ids
    }

    // ======================================================================
    // DUMP_ROOT_CHILDREN
    // ======================================================================
    if (MODE == "DUMP_ROOT_CHILDREN") {
        logLine("INFO", "DumpRootChildren", [Count: rootNodes.size()])
        rootNodes.eachWithIndex { g, i ->
            def n = safeProp(g, "name") as String
            logLine("INFO", "PG", [
                Index: i,
                Name: n,
                Norm: normalizeName(n),
                Len: n?.length(),
                GroupId: safeProp(g, "id"),
                ParentId: null,
                Depth: 0
            ])
        }
        logLine("INFO", "Complete", [Mode: MODE])
        return
    }

    // ======================================================================
    // DUMP_ALL_GROUPS
    // ======================================================================
    if (MODE == "DUMP_ALL_GROUPS") {
        def count = 0
        rootNodes.each { node ->
            walkGroups(node, null, 0) { g, parentId, depth ->
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
        }
        logLine("INFO", "DumpComplete", [Count: count])
        return
    }

    // ======================================================================
    // EXPORT
    // ======================================================================
    def groupsByNormName = [:].withDefault { [] }
    def indexed = 0

    rootNodes.each { node ->
        walkGroups(node, null, 0) { g, parentId, depth ->
            def n = safeProp(g, "name") as String
            groupsByNormName[normalizeName(n)] << g
            indexed++
        }
    }

    logLine("INFO", "Indexed", [Groups: indexed, UniqueNames: groupsByNormName.size()])

    def found = [] as Set

    TARGET_GROUPS.each { target ->
        def normTarget = normalizeName(target)
        def matches = groupsByNormName[normTarget] ?: []

        if (matches.isEmpty()) {
            logLine("WARN", "NotFound", [Group: target, Norm: normTarget])
            return
        }

        found.add(target)

        if (matches.size() > 1) {
            logLine("WARN", "DuplicateTargetName", [
                Group: target,
                MatchCount: matches.size(),
                GroupIds: matches.collect { safeProp(it, "id") }.join(",")
            ])
        }

        matches.each { grp ->
            def grpId = safeProp(grp, "id")
            def grpName = safeProp(grp, "name")

            def allIds = collectAllProcessorIds(grp)
            if (allIds.isEmpty()) {
                logLine("INFO", "Export", [Group: target, ActualName: grpName, GroupId: grpId, Result: "Empty"])
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

    logLine("INFO", "Complete", [Mode: MODE, Found: found.size(), Total: TARGET_GROUPS.size()])

} catch (Throwable t) {
    logLine("ERROR", "Exception", [Class: t.class.name, Msg: t.message])
    t.stackTrace.take(20).eachWithIndex { st, i ->
        logLine("ERROR", "ExceptionStack", [Index: i, At: st.toString()])
    }
}
