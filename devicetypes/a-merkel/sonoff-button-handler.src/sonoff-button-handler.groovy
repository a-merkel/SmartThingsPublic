/**
 *  Sonoff Button Handler
 *
 *  Copyright 2020 Adrian Merkel
 */

import physicalgraph.zigbee.clusters.iaszone.ZoneStatus
import physicalgraph.zigbee.zcl.DataType
import groovy.json.JsonOutput

metadata {
	definition (name: "Sonoff Button Handler", namespace: "a-merkel", author: "Adrian Merkel", cstHandler: true) {
		capability "Battery"
		capability "Button"
        capability "Configuration"
        capability "Holdable Button"
        capability "Refresh"
        capability "Health Check"
        capability "Sensor"
	}


	simulator {
		// TODO: define status and reply messages here
	}

    preferences {
        section {
            input ("holdTime", "number", title: "Minimum time in seconds for a press to count as \"held\"", defaultValue: 1, displayDuringSetup: false)
        }
    }

	tiles(scale: 2) {
        multiAttributeTile(name: "button", type: "generic", width: 6, height: 4) {
            tileAttribute("device.button", key: "PRIMARY_CONTROL") {
                attributeState "pushed", label: "Pressed", icon:"st.Weather.weather14", backgroundColor:"#53a7c0"
            }
        }
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
            state "battery", label: '${currentValue}% battery', unit: ""
        }
        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
        }

        main(["button"])
        details(["button", "battery", "refresh"])
    }
}

private List<Map> collectAttributes(Map descMap) {
    List<Map> descMaps = new ArrayList<Map>()

    descMaps.add(descMap)

    if (descMap.additionalAttrs) {
        descMaps.addAll(descMap.additionalAttrs)
    }

    return  descMaps
}

// parse events into attributes
def parse(String description) {
	log.debug "Parsing '${description}'"

    Map map = zigbee.getEvent(description)

    if (!map) {
        if (description?.startsWith('zone status')) {
            map = parseIasMessage(description)
        } else if ((description?.startsWith("catchall:")) || (description?.startsWith("read attr -"))) {
            Map descMap = zigbee.parseDescriptionAsMap(description)

            if (descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.commandInt != 0x07 && descMap.value) {
                List<Map> descMaps = collectAttributes(descMap)
                def battMap = descMaps.find { it.attrInt == 0x0020 }
                if (battMap) {
                    map = getBatteryResult(zigbee.convertHexToInt(battMap.value))
                }

                battMap = descMaps.find { it.attrInt == 0x0021 }
                if (battMap) {
                    map = getBatteryPercentageResult(zigbee.convertHexToInt(battMap.value))
                }

            } else if (descMap.clusterInt == 0x0006 || descMap.clusterInt == 0x0008) {
                map = parseNonIasButtonMessage(descMap)
            }
                
        } 
    }


	// TODO: handle 'battery' attribute
	// TODO: handle 'button' attribute
	// TODO: handle 'numberOfButtons' attribute
	// TODO: handle 'supportedButtonValues' attribute

    log.debug "Parse returned $map"
    def result = map ? createEvent(map) : [:]

    if (description?.startsWith('enroll request')) {
        List cmds = zigbee.enrollResponse()
        log.debug "enroll response: ${cmds}"
        result = cmds?.collect { new physicalgraph.device.HubAction(it) }
    }
    return result

}

private Map parseIasMessage(String description) {
    log.debug "parseIasMessage"
    ZoneStatus zs = zigbee.parseZoneStatus(description)

    return zs.isAlarm2Set() ? getButtonResult("press") : getButtonResult("release")
}

private Map parseNonIasButtonMessage(Map descMap){
    log.debug "parseIasMessage"
    def buttonState = ""
    def buttonNumber = 1

    if (descMap.clusterInt == 0x0006) {
        log.debug "clusterInt 0x0006 $descMap.command"
        buttonState = "pushed"
        if (descMap.command == "02") {
            buttonState = "pushed"
        }
        if (descMap.command == "00") {
            buttonState = "held"
        }
        else if (descMap.command == "01") {
            buttonState = "double"
        }
        if (buttonNumber !=0) {
            def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
            return createEvent(name: "button", value: buttonState, data: [buttonNumber: buttonNumber], descriptionText: descriptionText, isStateChange: true)
        }
        else {
            return [:]
        }
    }
    else if (descMap.clusterInt == 0x0008) {
        log.debug "clusterInt 0x0008 $descMap.command"
        if (descMap.command == "05") {
            state.buttonNumber = 1
            getButtonResult("press", 1)
        }
        else if (descMap.command == "01") {
            state.buttonNumber = 2
            getButtonResult("press", 2)
        }
        else if (descMap.command == "03") {
            getButtonResult("release", state.buttonNumber)
        }
    }

}

private Map getBatteryResult(rawValue) {
    log.debug "Battery rawValue = ${rawValue}"
    def linkText = getLinkText(device)

    def result = [:]

    def volts = rawValue / 10

    if (!(rawValue == 0 || rawValue == 255)) {
        result.name = 'battery'
        result.translatable = true
        result.descriptionText = "${ device.displayName } battery was ${ value }%"

        def minVolts = 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        def roundedPct = Math.round(pct * 100)
        if (roundedPct <= 0)
            roundedPct = 1
        result.value = Math.min(100, roundedPct)
    }

    return result
}

private Map getBatteryPercentageResult(rawValue) {
    log.debug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
    def result = [:]

    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.descriptionText = "{{ device.displayName }} battery was {{ value }}%"
        result.value = Math.round(rawValue / 2)
    }

    return result
}

private Map getButtonResult(buttonState, buttonNumber = 1) {
    if (buttonState == 'release') {
        log.debug "Button was value : $buttonState"
        if(state.pressTime == null) {
            return [:]
        }
        def timeDiff = now() - state.pressTime
        log.info "timeDiff: $timeDiff"
        def holdPreference = holdTime ?: 1
        log.info "holdp1 : $holdPreference"
        holdPreference = (holdPreference as int) * 1000
        log.info "holdp2 : $holdPreference"
        if (timeDiff > 10000) {         //timeDiff>10sec check for refresh sending release value causing actions to be executed
            return [:]
        }
        else {
            if (timeDiff < holdPreference) {
                buttonState = "pushed"
            }
            else {
                buttonState = "held"
            }
            def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
            return createEvent(name: "button", value: buttonState, data: [buttonNumber: buttonNumber], descriptionText: descriptionText, isStateChange: true)
        }
    }
    else if (buttonState == 'press') {
        log.debug "Button was value : $buttonState"
        state.pressTime = now()
        log.info "presstime: ${state.pressTime}"
        return [:]
    }
}

def installed() {
    sendEvent(name: "supportedButtonValues", value: ["pushed", "held", "double"].encodeAsJSON(), displayed: false)
    sendEvent(name: "numberOfButtons", value: 1, displayed: false)
//    device.currentValue("numberOfButtons")?.times {
//        sendEvent(name: "button", value: "pushed", data: [buttonNumber: it+1], displayed: false)
//    }
}

def configure() {
    // Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
    // enrolls with default periodic reporting until newer 5 min interval is confirmed
    // Sets up low battery threshold reporting
    //sendEvent(name: "DeviceWatch-Enroll", displayed: false, value: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, scheme: "untracked", checkInterval: 6 * 60 * 60 + 1 * 60, offlinePingable: "1"].encodeAsJSON())
    sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)

    return zigbee.onOffConfig() +
            zigbee.levelConfig() +
            zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20, DataType.UINT8, 30, 21600, 0x01) +
            zigbee.enrollResponse() +
            zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20)
}

def refresh() {
    log.debug "Refreshing Battery"

    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20) +
            zigbee.enrollResponse()
}