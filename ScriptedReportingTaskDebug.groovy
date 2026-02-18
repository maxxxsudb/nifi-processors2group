// ==========================================================================
// НАСТРОЙКИ
// ==========================================================================
def TARGET_GROUPS = ["Data Ingest", "Kafka Processing", "[BUS]_diss_groups"]
def DEBUG_ON_MISSING = true

// Единый тег, по которому фильтруете в Grafana
def TAG = "DEBUG_NIFI"

// ==========================================================================
// ЛОГИКА
// ==========================================================================
def eventAccess = context.getEventAccess()

def rootGroupStatus = null
try {
    rootGroupStatus = eventAccess.getControllerStatus().getProcessGroupStatus()
} catch (ignored) {
    try {
        rootGroupStatus = eventAccess.getGroupStatus("root")
    } catch (ignored2) {
        rootGroupStatus = null
    }
}

if (rootGroupStatus == null) {
    println "${TAG} Level=ERROR Msg='Could not obtain root group status'"
    return
}

// Нормализация имён (лечит NBSP и хвостовые пробелы)
def normalizeName = { String s ->
    if (s == null) return null
    s.replace('\u00A0', ' ')
     .replace('\u202F', ' ')
     .trim()
}

// Карта: нормализованное имя -> исходное имя из TARGET_GROUPS
def targetByNormalized = [:]
TARGET_GROUPS.each { t -> targetByNormalized[normalizeName(t)] = t }

def foundGroups = [] as Set
def foundTargetGroupIdsByName = [:].withDefault { [] }

def collectAllProcessorIds
collectAllProcessorIds = { groupStatus ->
    def ids = []
    if (groupStatus?.processorStatus) {
        ids.addAll(groupStatus.processorStatus.collect { it.id })
    }
    if (groupStatus?.processGroupStatus) {
        groupStatus.processGroupStatus.each { child ->
            ids.addAll(collectAllProcessorIds(child))
        }
    }
    return ids
}

def findTargets
findTargets = { groupStatus ->
    if (groupStatus == null) return

    def realName = groupStatus.name
    def normName = normalizeName(realName)

    if (targetByNormalized.containsKey(normName)) {
        def targetName = targetByNormalized[normName]
        foundGroups.add(targetName)

        // Совпало только после нормализации (если вдруг есть невидимые пробелы)
        if (realName != targetName) {
            println "${TAG} Level=WARN Type=NormalizeMatch Target='${targetName}' Actual='${realName}' ActualLen=${realName?.length()} GroupId='${groupStatus.id}'"
        }

        // Дубликаты целевых групп по имени
        def prevIds = foundTargetGroupIdsByName[targetName]
        if (prevIds && !prevIds.contains(groupStatus.id)) {
            println "${TAG} Level=WARN Type=DuplicateTargetName Group='${targetName}' GroupId='${groupStatus.id}' PreviousGroupIds='${prevIds.join(",")}'"
        }
        if (!prevIds.contains(groupStatus.id)) prevIds << groupStatus.id

        def allIds = collectAllProcessorIds(groupStatus)
        if (!allIds.isEmpty()) {
            def queryPart = "(" + allIds.join(" OR ") + ")"
            println "${TAG} Level=INFO Type=Export Group='${targetName}' GroupId='${groupStatus.id}' ProcessorCount=${allIds.size()} Query='${queryPart}'"
        } else {
            println "${TAG} Level=INFO Type=Export Group='${targetName}' GroupId='${groupStatus.id}' Result=Empty"
        }
    }

    (groupStatus.processGroupStatus ?: []).each { child ->
        findTargets(child)
    }
}

// ==========================================================================
// ЗАПУСК
// ==========================================================================
println "${TAG} Level=INFO Type=Start TargetGroups='${TARGET_GROUPS}'"
findTargets(rootGroupStatus)

def missingGroups = TARGET_GROUPS.findAll { !foundGroups.contains(it) }
missingGroups.each { name ->
    println "${TAG} Level=WARN Type=NotFound Group='${name}'"
}

if (missingGroups && DEBUG_ON_MISSING) {
    println "${TAG} Level=DEBUG Type=RootChildrenProcessGroups Count=${(rootGroupStatus.processGroupStatus ?: []).size()}"
    (rootGroupStatus.processGroupStatus ?: []).each { g ->
        def n = g.name
        println "${TAG} Level=DEBUG Type=RootPG Name='${n}' Len=${n?.length()} Id='${g.id}'"
    }

    // Если у вас “группа на канвасе” на самом деле Remote Process Group
    def rootRpg = []
    try { rootRpg = (rootGroupStatus.remoteProcessGroupStatus ?: []) } catch (ignored) { rootRpg = [] }

    println "${TAG} Level=DEBUG Type=RootChildrenRemoteProcessGroups Count=${rootRpg.size()}"
    rootRpg.each { r ->
        def n = r.name
        println "${TAG} Level=DEBUG Type=RootRPG Name='${n}' Len=${n?.length()} Id='${r.id}'"
    }
}

println "${TAG} Level=INFO Type=Complete Found=${foundGroups.size()} Total=${TARGET_GROUPS.size()}"
