

```groovy
// ==========================================================================
// НАСТРОЙКИ
// ==========================================================================
def TARGET_GROUPS = ["Data Ingest", "Kafka Processing", "My Group Name"]

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

// Map: имя группы → список найденных ID (для отслеживания дубликатов)
def foundGroups = [:].withDefault { [] }

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
    if (TARGET_GROUPS.contains(groupStatus.name)) {
        foundGroups[groupStatus.name].add(groupStatus.id)

        // Предупреждение о дубликате сразу при обнаружении
        if (foundGroups[groupStatus.name].size() > 1) {
            println "WARNING: Duplicate group '${groupStatus.name}' found! GroupId='${groupStatus.id}' (occurrence #${foundGroups[groupStatus.name].size()})"
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
            println "GRAFANA_EXPORT Group='" + groupStatus.name + "' GroupId='" + groupStatus.id + "' Result=Empty"
        }
        return
    }

    (groupStatus.processGroupStatus ?: []).each { child ->
        findTargets(child)
    }
}

println "--- Starting Scan for groups: ${TARGET_GROUPS} ---"
findTargets(rootGroupStatus)

def missingGroups = TARGET_GROUPS.findAll { !foundGroups.containsKey(it) }
if (missingGroups) {
    missingGroups.each { name ->
        println "WARNING: Group '${name}' was NOT found in the NiFi flow"
    }
}

println "--- Scan Complete. Found ${foundGroups.size()}/${TARGET_GROUPS.size()} groups ---"
```

## Что изменилось

| Было | Стало |
|---|---|
| `def foundGroups = [] as Set` | `def foundGroups = [:].withDefault { [] }` — Map, хранящий список ID для каждого имени |
| Дубликаты молча терялись | При повторном нахождении — сразу `WARNING: Duplicate group '...' found!` с указанием `GroupId` и номера вхождения |
| `TARGET_GROUPS - foundGroups` | `TARGET_GROUPS.findAll { !foundGroups.containsKey(it) }` — корректная проверка по Map |

## Пример вывода при дубликате

```
--- Starting Scan for groups: [Data Ingest, Kafka Processing, My Group Name] ---
GRAFANA_EXPORT Group='Data Ingest' GroupId='a1b2c3d4-...' ProcessorCount=12 Query='(...)'
WARNING: Duplicate group 'Data Ingest' found! GroupId='f9e8d7c6-...' (occurrence #2)
GRAFANA_EXPORT Group='Data Ingest' GroupId='f9e8d7c6-...' ProcessorCount=3 Query='(...)'
WARNING: Group 'My Group Name' was NOT found in the NiFi flow
--- Scan Complete. Found 2/3 groups ---
```
