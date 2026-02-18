// ==========================================================================
// НАСТРОЙКИ
// ==========================================================================
def TARGET_GROUPS = ["Data Ingest", "Kafka Processing", "[BUS]_diss_groups"]

// ==========================================================================
// ЛОГИКА
// ==========================================================================

def eventAccess = context.getEventAccess()

def rootGroupStatus = null
try {
    rootGroupStatus = eventAccess.getControllerStatus().getProcessGroupStatus()
} catch (ignored) {
    rootGroupStatus = eventAccess.getGroupStatus("root")
}

if (rootGroupStatus == null) {
    println "ERROR: Could not obtain root group status"
    return
}

def foundGroups = [] as Set
def foundTargetGroupIdsByName = [:].withDefault { [] }

def collectAllProcessorIds
collectAllProcessorIds = { groupStatus ->
    def ids = []
    if (groupStatus.processorStatus) {
        ids.addAll(groupStatus.processorStatus.collect { it.id })
    }
    if (groupStatus.processGroupStatus) {
        groupStatus.processGroupStatus.each { child ->
            ids.addAll(collectAllProcessorIds(child))
        }
    }
    return ids
}

def findTargets
findTargets = { groupStatus ->
    if (groupStatus == null) return

    // 1) Если это целевая группа — выводим
    if (TARGET_GROUPS.contains(groupStatus.name)) {
        foundGroups.add(groupStatus.name)

        // Дубликаты по имени: name -> список найденных groupId
        def prevIds = foundTargetGroupIdsByName[groupStatus.name]
        if (prevIds && !prevIds.contains(groupStatus.id)) {
            println(
                "WARNING: Duplicate target group name found. " +
                "Group='${groupStatus.name}' " +
                "GroupId='${groupStatus.id}' " +
                "PreviousGroupIds='${prevIds.join(",")}'"
            )
        }
        if (!prevIds.contains(groupStatus.id)) {
            prevIds << groupStatus.id
        }

        def allIds = collectAllProcessorIds(groupStatus)
        if (!allIds.isEmpty()) {
            def queryPart = "(" + allIds.join(" OR ") + ")"
            println(
                "GRAFANA_EXPORT " +
                "Group='" + groupStatus.name + "' " +
                "GroupId='" + groupStatus.id + "' " +
                "ProcessorCount=" + allIds.size() + " " +
                "Query='" + queryPart + "'"
            )
        } else {
            println "GRAFANA_EXPORT Group='${groupStatus.name}' GroupId='${groupStatus.id}' Result=Empty"
        }
    }

    // 2) Всегда идём вглубь — находим вложенные целевые группы
    (groupStatus.processGroupStatus ?: []).each { child ->
        findTargets(child)
    }
}

// ==========================================================================
// ЗАПУСК
// ==========================================================================
println "--- Starting Scan for groups: ${TARGET_GROUPS} ---"
findTargets(rootGroupStatus)

def missingGroups = TARGET_GROUPS.findAll { !foundGroups.contains(it) }
if (missingGroups) {
    missingGroups.each { name ->
        println "WARNING: Group '${name}' was NOT found in the NiFi flow"
    }
}

println "--- Scan Complete. Found ${foundGroups.size()}/${TARGET_GROUPS.size()} groups ---"
