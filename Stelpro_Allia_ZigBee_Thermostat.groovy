/**
 *  Copyright 2022 Maxime Boissonneault
 *  Copyright 2020 Philippe Charette
 *  Copyright 2018 Stelpro
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Stelpro Maestro Thermostat Hubitat Driver
 *
 *  Notice: This file is a modified version of the SmartThings Device Hander, found in this repository:
 *.          https://github.com/Philippe-Charette/Hubitat-Stelpro-Maestro-Thermostat
 *.          which itself came from this repository:
 *           https://github.com/stelpro/maestro-thermostat
 *
 *  Author: Maxime Boissonneault
 *  Author: Philippe Charette
 *  Author: Stelpro
 *
 *  Date: 2022-12-06
 */

import groovy.transform.Field
import groovy.json.JsonOutput

metadata {
    definition (name: "Stelpro Allia ZigBee Thermostat", namespace: "Mboisson", author: "Mboisson") {
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatOperatingState"
        //capability "ThermostatSetpoint"
        capability "ThermostatMode"
        capability "Thermostat"
        capability "Refresh"
        capability "PowerMeter"
        capability "EnergyMeter"
        //capability "SwitchLevel"

        fingerprint profileId: "0104", endpointId: "19", inClusters: "0000,0003,0004,0201,0204", outClusters: "0003,000A,0402"
        attribute "temperature_display_mode", "string"
        attribute "keypad_lockout", "string"
        attribute "outdoor_temperature", "number"
        attribute "supportedThermostatFanModes", "JSON_OBJECT"
		attribute "supportedThermostatModes", "JSON_OBJECT"
        // 0x0000 (0): Basic
        // 0x0003 (3): Indentify
        // 0x000A (10):   Time
        // 0x0201 (513): Thermostat
        // 0x0204 (516): Thermostat User Interface Configuration
        // 0x0402 (1026): Temperature Measurement
        // 0x0405 (1029): Relative Humidity Measurement
        // 0x0B04 (2820): Electrical Measurement
        
        command "setOutdoorTemperature", [[name:"Temperature", type:"NUMBER"]]
        command "setThermostatMode", [[name:"Thermostat mode*","type":"ENUM","description":"Thermostat mode to set","constraints":["heat"]]]
        command "setThermostatOperatingState", [[name:"Thermostat mode*","type":"ENUM","description":"Thermostat mode to set","constraints":["heating","idle"]]]
        command "setThermostatFanMode", [[name:"Thermostat mode*","type":"ENUM","description":"Thermostat mode to set","constraints":["auto"]]]

        command "increaseHeatSetpoint"
        command "decreaseHeatSetpoint"
    }
    
    preferences {
//        input("lock", "enum", title: "Do you want to lock your thermostat's physical keypad?", options: ["No", "Yes"], defaultValue: "No", required: false, displayDuringSetup: false)
//        input("country", "enum", title: "Country (Outdoor Temperature)", options: ["Canada", "United States"], defaultValue: "Canada", required: false, displayDuringSetup: true)
//        input("zipcode", "text", title: "ZipCode (Outdoor Temperature)", description: "[Do not use space](Blank = No Forecast)")
        input name: "tempChange", type: "number", title: "Temperature change", description: "Minumum change of temperature reading to trigger report in Celsius/100, 10..200", range: "10..200", defaultValue: 100
        input name: "powerReport", type: "number", title: "Power change", description: "Amount of wattage difference to trigger power report (1..*)",  range: "1..*", defaultValue: 30
        input name: "reportingSeconds", type: "enum", title: "Status reporting", description: "Maximum time to report status even if no change", options:[0: "never", 60: "1 minute", 600:"10 minutes", 3600:"1 hour", 21600:"6 hours", 43200:"12 hours", 86400:"24 hours"], defaultValue: "60", multiple: false, required: true
        input name: 'refreshScheduleHeating', type: 'enum', title: '<b>Refresh Interval while heating</b>', options: RefreshIntervalOpts.options, defaultValue: RefreshIntervalOpts.defaultValueHeating, description:\
            '<i>Changes how often the hub calls a refresh while the thermostat is in heating mode.</i>'
        input name: 'refreshScheduleIdle', type: 'enum', title: '<b>Refresh Interval while idle</b>', options: RefreshIntervalOpts.options, defaultValue: RefreshIntervalOpts.defaultValueIdle, description:\
            '<i>Changes how often the hub calls a refresh while the thermostat is in idle mode.</i>'
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}
    

def parse(String description) {
    logDebug "Parse description $description"
    def descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "Desc Map: $descMap"
    def map = [:]
    if (description?.startsWith("read attr -")) {
        if (descMap.cluster == "0201" && descMap.attrId == "0000")
        {
            map.name = "temperature"
            map.value = getTemperature(descMap.value)
            if (descMap.value == "7FFD") {       //0x7FFD
                map.value = "low"
            }
            else if (descMap.value == "7FFF") {  //0x7FFF
                map.value = "high"
            }
            else if (descMap.value == "8000") {  //0x8000
                map.value = "--"
            }
            
            else if (descMap.value > "8000") {
                map.value = -(Math.round(2*(655.36 - map.value))/2)
            }
                        
            sendEvent(name:"temperature", value:map.value)
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
            logDebug "HEATING SETPOINT"
            map.name = "heatingSetpoint"
            map.value = getTemperature(descMap.value)
            if (descMap.value == "8000") {        //0x8000
                map.value = getTemperature("01F4")  // 5 Celsius (minimum setpoint)
            }
            sendEvent(name:"heatingSetpoint", value:map.value)
            sendEvent(name:"thermostatSetpoint", value:map.value)
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "0008") {
            logDebug "HEAT DEMAND"
            map.name = "thermostatOperatingState"
            //map.value = getModeMap()[descMap.value]
            if (descMap.value < "10") {
                map.value = "idle"
                final int interval = (settings.refreshScheduleIdle as Integer) ?: 0
                if (interval > 0 && map.value != device.currentValue("thermostatOperatingState")) {
                    log.info "${device} scheduling refresh every ${interval} minutes"
                    scheduleRefresh(interval)
                    runIn(5, 'refresh')
                }
            }
            else {
                map.value = "heating"
                final int interval = (settings.refreshScheduleHeating as Integer) ?: 0
                if (interval > 0 && map.value != device.currentValue("thermostatOperatingState")) {
                    log.info "${device} scheduling refresh every ${interval} minutes"
                    scheduleRefresh(interval)
                    runIn(5, 'refresh')
                }
            }
            sendEvent(name:"thermostatOperatingState", value:map.value)
        }
        else if (descMap.cluster == "0204" && descMap.attrId == "0000") {
            map.value = getTemperatureDisplayModeMap()[descMap.value]
            sendEvent(name:"temperature_display_mode", value:map.value)
            logDebug "TEMPERATURE DISPLAY MODE: ${map.value}, $descMap"
        }
        else if (descMap.cluster == "0204" && descMap.attrId == "0001") {
            map.value = getKeypadLockoutMap()[descMap.value]
            sendEvent(name:"keypad_lockout", value:map.value)
            logDebug "KEYPAD LOCKOUT: ${map.value}, $descMap"
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "4001") {
            map.value = getTemperature(descMap.value)
            if (map.value > 100) {
                map.value = map.value - 655.36 // handle negative temperatures
            }
            logDebug "OUTDOOR_TEMPERATURE: ${map.value}, $descMap"
            sendEvent(name:"outdoor_temperature", value:map.value)
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "4004") {
            value = descMap.value
            logDebug "UNKNOWN 4004: ${map.value}, $descMap"
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "4008") {
            map.value = getPower(descMap.value)
            logDebug "POWER: ${map.value}, $descMap"
            sendEvent(name:"power", value:map.value)
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "4009") {
            map.value = getEnergy(descMap.value)
            logDebug "ENERGY: ${map.value}, $descMap"
            sendEvent(name:"energy", value:map.value)
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "4002") {
            value = descMap.value
            logDebug "UNKNOWN 4002: $value, $descMap"
        }

        else {
            logDebug "UNKNOWN: $descMap"
        }
    }

    def result = null
    if (map) {
        result = createEvent(map)
    }
    logDebug "Parse returned $map"
    return result
}

/**
 * Schedule a refresh
 * @param intervalMin interval in minutes
 */
private void scheduleRefresh(final int intervalMin) {
    final Random rnd = new Random()
    unschedule('refresh')
    log.info "${rnd.nextInt(59)} ${rnd.nextInt(intervalMin)}-59/${intervalMin} * ? * * *"
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(intervalMin)}-59/${intervalMin} * ? * * *", 'refresh')
}


def logsOff(){
   log.warn "debug logging disabled..."
   device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def refresh() {
    log.info("refresh")
    def cmds = []
    
    cmds += zigbee.readAttribute(0x201, 0x0000) //Read Local Temperature
    cmds += zigbee.readAttribute(0x201, 0x0008) //Read PI Heating State  
    cmds += zigbee.readAttribute(0x201, 0x0012) //Read Heat Setpoint
    
    cmds += zigbee.readAttribute(0x204, 0x0000) //Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x204, 0x0001) //Read Keypad Lockout
    
    cmds += zigbee.readAttribute(0x201, 0x4001) // Outdoor temperature
//    cmds += zigbee.readAttribute(0x201, 0x4002) // unknown
//    cmds += zigbee.readAttribute(0x201, 0x4004) // unknown
    cmds += zigbee.readAttribute(0x201, 0x4008) // power
    cmds += zigbee.readAttribute(0x201, 0x4009) // energy
    logDebug "refresh cmds:${cmds}"

    return cmds
}

def logDebug(value){
    if (logEnable) log.debug(value)
}

def setSupportedThermostatFanModes(fanModes) {
	logDebug "setSupportedThermostatFanModes(${fanModes}) was called"
	// (auto, circulate, on)
	sendEvent(name: "supportedThermostatFanModes", value: fanModes, descriptionText: getDescriptionText("supportedThermostatFanModes set to ${fanModes}"))
}

def setSupportedThermostatModes(modes) {
	logDebug "setSupportedThermostatModes(${modes}) was called"
	// (auto, cool, emergency heat, heat, off)
	sendEvent(name: "supportedThermostatModes", value: modes, descriptionText: getDescriptionText("supportedThermostatModes set to ${modes}"))
}

def configure(){    
    log.warn "configure..."
    unschedule()
    runIn(1800,logsOff)    
    logDebug "binding to Thermostat cluster"

    // Configure Default values if null
    if (tempChange == null)
        tempChange = 100 as int
    if (powerReport == null)
        powerReport = 30 as int
    if (reportingSeconds == null)
        reportingSeconds = "60"

    
    // Set unused default values (for Google Home Integration)
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto")
    setThermostatMode("heat")
    setSupportedThermostatFanModes(JsonOutput.toJson(["auto"]))
    setSupportedThermostatModes(JsonOutput.toJson(["heat"]))
    def cmds = [
    //bindings
        "zdo bind 0x${device.deviceNetworkId} 1 0x019 0x201 {${device.zigbeeId}} {}", "delay 200",
//        "zdo bind 0x${device.deviceNetworkId} 1 0x0F2 0x0021 {${device.zigbeeId}} {}", "delay 200"
        ]
    
    //reporting
    //cmds += zigbee.configureReporting(0x0402, 0x0000, 0x29, 30, 580, (int) tempChange)                      // Water temperature
    //cmds += zigbee.configureReporting(0x0500, 0x0002, DataType.BITMAP16, 0, Integer.parseInt(waterReportingSeconds))  // Water lear sensor state
    //cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, Integer.parseInt(switchReportingSeconds))    // Heater On/off state
    //cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 30, 600, (int) powerReport)                     // Active power reporting
    //cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 299, 1799, (int) energyChange)       // Energy reading
    //cmds += zigbee.configureReporting(0xFF01, 0x0076, DataType.UINT8, 0, 86400, null, [mfgCode: "0x119C"])  // Safety water temp reporting every 24 hours

    
    cmds += zigbee.configureReporting(0x201, 0x0000, 0x29, 10, Integer.parseInt(reportingSeconds), (int) tempChange)   //Attribute ID 0x0000 = local temperature, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x0008, 0x20, 10, 900, 5)   //Attribute ID 0x0008 = pi heating demand, Data Type: U8BIT
    cmds += zigbee.configureReporting(0x201, 0x0012, 0x29, 10, 60, 1)     //Attribute ID 0x0012 = occupied heat setpoint, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x4008, 0x29, 10, Integer.parseInt(reportingSeconds), (int) powerReport)     //Attribute ID 0x4008 = power usage, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x4009, 0x29, 10, Integer.parseInt(reportingSeconds), 10)     //Attribute ID 0x4009 = energy usage, Data Type: S16BIT
    
    cmds += zigbee.configureReporting(0x204, 0x0000, 0x30, 10, 900)         //Attribute ID 0x0000 = temperature display mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x204, 0x0001, 0x30, 10, 900)         //Attribute ID 0x0001 = keypad lockout, Data Type: 8 bits enum
    logDebug "cmds:${cmds}"
    return cmds + refresh()
}

def getModeMap() { [
    "00":"off",
    "04":"heat",
    "05":"eco"
]}
def getFanModeMap() {[
    "00":"off",
]}
def getTemperatureDisplayModeMap() { [
    "00":"C",
    "01":"F"
]}

def getKeypadLockoutMap() { [
    "00":"unlocked",
    "01":"locked"
]}

def getPower(value)
{
    if (value != null) {
        logDebug("getPower: value $value")
        def power = Integer.parseInt(value, 16)
        return power
    }
}
def getEnergy(value)
{
    if (value != null) {
        logDebug("getEnergy: value $value")
        def energy = Integer.parseInt(value, 16)/1000
        return energy
    }
}
def getTemperature(value) {
    if (value != null) {
        logDebug("getTemperature: value $value")
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

def getTemperatureScale() {
    return "${location.temperatureScale}"
}
//def setSchedule(JSON_OBJECT){
//    log.info "setSchedule is not available for this device"
//}
def setThermostatMode(mode) {
     sendEvent(name:"thermostatMode", value:mode)
}
def setThermostatOperatingState(state) {
    if (state == "on") { state = "idle" }
     sendEvent(name:"thermostatOperatingState", value:state)
}
def setHeatingSetpoint(preciseDegrees) {
    if (preciseDegrees != null) {
        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
        
        logDebug "setHeatingSetpoint(${degrees} ${temperatureScale})"
        
        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)
        
        zigbee.writeAttribute(0x201, 0x0012, 0x29, celsius100) //Write Heat Setpoint 
    }
}
def increaseHeatSetpoint() {
    def currentSetpoint = device.currentValue("heatingSetpoint")
    def locationScale = getTemperatureScale()
    def maxSetpoint
    def step

    if (locationScale == "C") {
        maxSetpoint = 30;
        step = 0.5
    }
    else {
        maxSetpoint = 86
        step = 1
    }

    if (currentSetpoint < maxSetpoint) {
        currentSetpoint = currentSetpoint + step
        setHeatingSetpoint(currentSetpoint)
    }   
}
def decreaseHeatSetpoint() {
    def currentSetpoint = device.currentValue("heatingSetpoint")
    def locationScale = getTemperatureScale()
    def minSetpoint
    def step

    if (locationScale == "C") {
        minSetpoint = 5;
        step = 0.5
    }
    else {
        minSetpoint = 41
        step = 1
    }

    if (currentSetpoint > minSetpoint) {
        currentSetpoint = currentSetpoint - step
        setHeatingSetpoint(currentSetpoint)
    }
}
def setOutdoorTemperature(preciseDegrees) {
    if (preciseDegrees != null) {
        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
        
        logDebug "setOutdoorTemperature(${degrees} ${temperatureScale})"
        
        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)
        
        zigbee.writeAttribute(0x201, 0x4001, 0x29, celsius100) //, ["mfgCode": "0x1185"])
    }
}

def updated() {
    parameterSetting()
}

def parameterSetting() {
    def lockmode = null
    def valid_lock = 0

    log.info "lock : $settings.lock"
    if (settings.lock == "Yes") {
        lockmode = 0x01
        valid_lock = 1
    }
    else if (settings.lock == "No") {
        lockmode = 0x00
        valid_lock = 1
    }
    
    if (valid_lock == 1)
    {
        log.info "lock valid"
        def cmds = []
        
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, lockmode)    //Write Lock Mode
        cmds+= refresh()
        return cmds
    }
    else {
        log.info "nothing valid"
    }
}

// required useless functions
def auto() {}
def cool() {}
def emergencyHeat() {}
def fanAuto() {}
def fanCirculate() {}
def fanOn() {}
def heat() { setHeatingSetpoint(device.currentValue("temperature")+0.5) }
def off() { setHeatingSetpoint(5) }
def setThermostatFanMode(mode) {
     sendEvent(name:"thermostatFanMode", "off")
}

private getDescriptionText(msg) {
	def descriptionText = "${device.displayName} ${msg}"
	if (settings?.txtEnable) log.info "${descriptionText}"
	return descriptionText
}

@Field static final Map RefreshIntervalOpts = [
    defaultValueIdle: 10,
    defaultValueHeating: 1,
    options: [ 1: 'Every 1 Min', 5: 'Every 5 Mins', 10: 'Every 10 Mins', 15: 'Every 15 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]
