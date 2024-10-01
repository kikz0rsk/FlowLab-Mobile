package com.danizek.dp

class Forwarder {
    var rules: List<ForwardRule> = ArrayList<ForwardRule>()

    fun addRule(rule: ForwardRule) {
        rules.add(rule)
    }
}