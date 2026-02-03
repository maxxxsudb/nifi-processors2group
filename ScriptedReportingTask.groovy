import groovy.json.JsonOutput

def eventAccess = context.getEventAccess()

// root: пробуем через controllerStatus, иначе fallback на "root"
def rootGroupStatus = null
try {
    rootGroupStatus = eventAccess.getControllerStatus().getProcessGroupStatus()
} catch (ignored) {
    rootGroupStatus = eventAccess.getGroupStatus("root")
}

def SEP = "/"

def esc = { String s ->
    if (s == null) return ""
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}

def walk
walk = { groupStatus, parentParts ->
    def parts = parentParts + [groupStatus.name ?: ""]
    def groupPath = parts.join(SEP)

    def procs = (groupStatus.processorStatus ?: []).collect { ps ->
        [id: ps.id?.toString(), name: ps.name?.toString()]
    }

    if (!procs.isEmpty()) {
        def processorsJson = JsonOutput.toJson(procs)
        def parentsJson    = JsonOutput.toJson(parts)

        println(
            'NIFI_INVENTORY ' +
            'groupId="' + esc(groupStatus.id?.toString()) + '" ' +
            'groupPath="' + esc(groupPath) + '" ' +
            'parentsJson="' + esc(parentsJson) + '" ' +
            'processorCount=' + procs.size() + ' ' +
            'processorsJson="' + esc(processorsJson) + '"'
        )
    }

    (groupStatus.processGroupStatus ?: []).each { child ->
        walk(child, parts)
    }
}

walk(rootGroupStatus, [])
