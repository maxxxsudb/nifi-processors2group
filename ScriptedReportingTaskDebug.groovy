// ==========================================================================
// НАСТРОЙКИ
// ==========================================================================
def TARGET_GROUPS = ["Data Ingest", "Kafka Processing", "[BUS]_diss_groups"]

def TAG = "DEBUG_NIFI"
def DEBUG_PRINT_ROOT_GROUPS = false  // true, если хотите увидеть группы верхнего уровня

// ==========================================================================
// ROOT STATUS
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
    println "${TAG} Level=ERROR Type=Init Msg='Could not obtain root group status'"
    return
}

// ==========================================================================
// HELPERS
// ==========================================================================
def normalizeName = { String s ->
    if (s == null) return null
    s.replace('\u00A0', ' ')
     .replace('\u202F', ' ')
     .trim()
}

// Собрать processor IDs во всей глубине выбранной группы
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

// ==========================================================================
// 1) Индексация всех групп: name(normalized) -> список groupStatus
// ==========================================================================
def groupsByNormName = [:].withDefault { [] }

def indexGroups
indexGroups = { groupStatus ->
    if (groupStatus == null) return

    def norm = normalizeName(groupStatus.name)
    groupsByNormName[norm] << groupStatus

    (groupStatus.processGroupStatus ?: []).each { child ->
        indexGroups(child)
    }
}

println "${TAG} Level=INFO Type=Start TargetGroups='${TARGET_GROUPS}'"
indexGroups(rootGroupStatus)

// (опционально) список корневых групп
if (DEBUG_PRINT_ROOT_GROUPS) {
    (rootGroupStatus.processGroupStatus ?: []).each { g ->
        println "${TAG} Level=DEBUG Type=RootPG Name='${g.name}' Norm='${normalizeName(g.name)}' GroupId='${g.id}'"
    }
}

// ==========================================================================
// 2) Выбор target-групп из индекса + экспорт процессоров
// ==========================================================================
def foundTargetNames = [] as Set

TARGET_GROUPS.each { target ->
    def normTarget = normalizeName(target)
    def matches = groupsByNormName[normTarget] ?: []

    if (matches.isEmpty()) {
        println "${TAG} Level=WARN Type=NotFound Group='${target}'"
        return
    }

    foundTargetNames.add(target)

    if (matches.size() > 1) {
        println "${TAG} Level=WARN Type=DuplicateTargetName Group='${target}' MatchCount=${matches.size()} GroupIds='${matches.collect{it.id}.join(",")}'"
    }

    matches.each { grp ->
        def allIds = collectAllProcessorIds(grp)
        if (!allIds.isEmpty()) {
            def queryPart = "(" + allIds.join(" OR ") + ")"
            println "${TAG} Level=INFO Type=Export Group='${target}' ActualName='${grp.name}' GroupId='${grp.id}' ProcessorCount=${allIds.size()} Query='${queryPart}'"
        } else {
            println "${TAG} Level=INFO Type=Export Group='${target}' ActualName='${grp.name}' GroupId='${grp.id}' Result=Empty"
        }
    }
}

println "${TAG} Level=INFO Type=Complete Found=${foundTargetNames.size()} Total=${TARGET_GROUPS.size()}"
