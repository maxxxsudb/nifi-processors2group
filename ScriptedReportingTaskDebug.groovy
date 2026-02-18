// ==========================================================================
// ОТЛАДКА — удалить после решения проблемы
// ==========================================================================
println "=== DEBUG START ==="
println "ROOT name='" + rootGroupStatus.name + "' id='" + rootGroupStatus.id + "'"
println "ROOT has children: " + (rootGroupStatus.processGroupStatus != null)
println "ROOT children count: " + (rootGroupStatus.processGroupStatus?.size() ?: 0)

rootGroupStatus.processGroupStatus?.each { child ->
    // Выводим имя в кавычках + побайтово, чтобы увидеть скрытые символы
    def nameBytes = child.name.bytes.collect { String.format('%02X', it) }.join(' ')
    println "  CHILD name='" + child.name + "' id='" + child.id + "' nameHex=[${nameBytes}]"
}

// Также выведем целевое имя побайтово для сравнения
TARGET_GROUPS.each { target ->
    def targetBytes = target.bytes.collect { String.format('%02X', it) }.join(' ')
    println "  TARGET name='" + target + "' nameHex=[${targetBytes}]"
}
println "=== DEBUG END ==="
