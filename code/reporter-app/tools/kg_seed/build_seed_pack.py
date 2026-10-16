#!/usr/bin/env python3
"""Deterministic generator of the IIIT-H demo knowledge-graph seed pack.

Writes (all paths relative to the repo root):
  app/src/main/assets/kg/seed_pack.json            the GraphPack
  app/src/test/resources/kg/scenarios.json         contract fixtures (report -> incident -> expected recommendations)

Run:  python tools/kg_seed/build_seed_pack.py
The committed JSON files MUST equal the output of this script. The script ends with a self-check (python only) and exits non-zero on
any violation. Nothing here is a real SOP, contact, capacity or room number (see app/src/main/assets/kg/README.md).
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PACK_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "kg", "seed_pack.json")
SCEN_PATH = os.path.join(ROOT, "app", "src", "test", "resources", "kg", "scenarios.json")

DEMO = "sop:demo-seed:v1"
DEMO_BY = "demo-seed (not a real SOP)"
DEMO_AT = "2026-10-11T00:00:00+05:30"
ALL = ["ACTIVE", "CONTAINED", "RESOLVED", "UNKNOWN"]
POST = ["CONTAINED", "RESOLVED"]
POSTU = ["CONTAINED", "RESOLVED", "UNKNOWN"]

nodes, edges, nodes_by_id = [], [], {}
edge_ids = {}


def slug(code):
    return code.lower().replace("_", "-")


def node(nid, ntype, name, props):
    assert nid not in nodes_by_id, "duplicate node " + nid
    n = {"id": nid, "type": ntype, "name": name, "properties": props,
         "verificationStatus": "NEEDS_ADMIN_VERIFICATION", "version": 1, "active": True}
    nodes.append(n)
    nodes_by_id[nid] = n
    return n


def edge(src, rel, dst, cond=None, prio=50, ref=DEMO):
    base = "edge:%s>%s>%s" % (src, rel, dst)
    k = edge_ids.get(base, 0) + 1
    edge_ids[base] = k
    eid = base if k == 1 else "%s#%d" % (base, k)
    edges.append({"id": eid, "sourceId": src, "relation": rel, "targetId": dst, "conditions": cond or {},
                  "priority": prio, "sourceRef": ref, "approvalStatus": "APPROVED", "version": 1, "active": True})


# ---------------------------------------------------------------------------------------------------------------- sources
SOURCES = [{"id": DEMO, "title": "Demo guidance (generic, not a real IIIT-H SOP)", "version": "1", "uri": None,
            "approvedBy": DEMO_BY, "approvedAt": DEMO_AT, "expiresAt": None, "status": "APPROVED",
            "displayLabel": "Demo guidance - pending IIIT-H admin approval"}]
for sid, title in [
    ("sop:fire-safety:v1", "Fire safety and evacuation procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:electrical-safety:v1", "Electrical safety and isolation procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:chemical-spill:v1", "Chemical spill response procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:lpg-safety:v1", "LPG and gas safety procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:it-continuity:v1", "IT service continuity procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:water-plumbing:v1", "Water and plumbing response procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:sports-injury:v1", "Sports injury response procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:hostel-safety:v1", "Hostel safety procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:mess-safety:v1", "Mess and food safety procedure (admin must attach the approved IIIT-H SOP)"),
    ("sop:building-compliance:v1", "Building and exit compliance procedure (admin must attach the approved IIIT-H SOP)"),
]:
    SOURCES.append({"id": sid, "title": title, "version": "1", "uri": None, "approvedBy": None, "approvedAt": None,
                    "expiresAt": None, "status": "NEEDS_ADMIN_VERIFICATION",
                    "displayLabel": "Placeholder - IIIT-H admin must attach the approved SOP"})

# ---------------------------------------------------------------------------------------------------------------- teams
TEAMS = [("IT_OPERATIONS", "IT operations"), ("ELECTRICAL_MAINTENANCE", "Electrical maintenance"),
         ("FIRE_SAFETY", "Fire safety"), ("CAMPUS_SECURITY", "Campus security"), ("FACILITIES", "Facilities"),
         ("PLUMBING", "Plumbing"), ("MEDICAL_SERVICES", "Campus medical services"), ("MESS_MANAGEMENT", "Mess management"),
         ("LAB_SAFETY", "Lab safety"), ("HOSTEL_ADMIN", "Hostel administration"), ("ACADEMIC_OFFICE", "Academic office"),
         ("SPORTS_FACILITIES", "Sports facility staff")]
for role, label in TEAMS:
    node("team:" + slug(role), "RESPONSE_TEAM", label, {"role": role, "label": label})


def tid(role):
    return "team:" + slug(role)


# ---------------------------------------------------------------------------------------------------------------- campus
node("campus:iiit-h", "CAMPUS", "IIIT Hyderabad campus (generic)", {"note": "Broad, non-sensitive entities only; admin must verify."})
BUILDINGS = [("nilgiri", "Nilgiri", ["Nilgiri"], "ACADEMIC_BUILDING"), ("himalaya", "Himalaya", ["Himalaya"], "ACADEMIC_BUILDING"),
             ("krb", "Kohli Research Block", ["KRB", "Kohli Research Block"], "RESEARCH_BUILDING"),
             ("hostels-area", "Hostel blocks (area)", [], "AREA"), ("mess-area", "Mess and food facilities (area)", [], "AREA"),
             ("outdoor-area", "Outdoor grounds (area)", [], "AREA"), ("library", "Library", [], "AREA"),
             ("health-facility", "Campus health facility", [], "AREA")]
for b, name, aliases, kind in BUILDINGS:
    node("building:" + b, "BUILDING", name, {"aliases": aliases, "kind": kind})
    edge("campus:iiit-h", "CONTAINS", "building:" + b, {}, 50, None)

# ---------------------------------------------------------------------------------------------------------------- vocab
INCIDENT_TYPES = [  # code, name, category, description
    ("CLASSROOM_FIRE", "Classroom fire", "FIRE", "Fire or smoke in a classroom or seminar space."),
    ("HOSTEL_FIRE", "Hostel fire or smoke", "FIRE", "Fire or smoke in a hostel room, corridor or common area."),
    ("COMPUTER_FIRE", "Computer fire", "FIRE", "Fire or smoke from a desktop, laptop or workstation."),
    ("SERVER_RACK_FIRE", "Server rack fire", "FIRE", "Fire or smoke from server or network rack equipment."),
    ("KITCHEN_FIRE", "Kitchen fire", "FIRE", "Fire or smoke in a kitchen, canteen or dining facility."),
    ("COOKING_FIRE", "Cooking fire", "FOOD", "Fire involving cooking equipment or hot oil."),
    ("ELECTRICAL_PANEL_FIRE", "Electrical panel fire", "FIRE", "Fire or smoke from an electrical panel or distribution board."),
    ("FIRE_SMOKE", "Fire or smoke (general)", "FIRE", "Fire or smoke that matches no more specific type."),
    ("SPARKING_SOCKET", "Sparking socket", "ELECTRICAL", "Sparks from a socket, switch or plug."),
    ("EXPOSED_WIRE", "Exposed wire", "ELECTRICAL", "Exposed, bare or loose wiring."),
    ("OVERLOADED_EXTENSION", "Overloaded extension", "ELECTRICAL", "Overloaded or overheating extension board or power strip."),
    ("WATER_NEAR_POWER", "Water near power", "ELECTRICAL", "Water close to sockets, power strips or other power equipment."),
    ("UPS_OVERHEATING", "UPS overheating", "ELECTRICAL", "Overheating of a power backup unit."),
    ("ELECTRICAL_FAULT", "Electrical fault (general)", "ELECTRICAL", "Electrical problem that matches no more specific type."),
    ("PIPE_LEAK", "Pipe leak", "WATER", "Leak from a pipe or supply line."),
    ("CEILING_LEAK", "Ceiling leak", "WATER", "Water leaking through a ceiling."),
    ("WASHROOM_FLOODING", "Washroom flooding", "WATER", "Flooding or overflow in a washroom."),
    ("BLOCKED_DRAIN", "Blocked drain", "WATER", "Blocked or overflowing drain."),
    ("DRINKING_WATER_CONCERN", "Drinking water concern", "WATER", "Concern about the safety of a drinking water source."),
    ("WATER_LEAK", "Water leak (general)", "WATER", "Water leak that matches no more specific type."),
    ("FALL_INJURY", "Fall", "MEDICAL", "A person fell or slipped."),
    ("COLLISION_INJURY", "Collision", "MEDICAL", "A person was involved in a collision."),
    ("BLEEDING", "Bleeding", "MEDICAL", "A person is reported to be bleeding."),
    ("UNCONSCIOUS_PERSON", "Unconscious person", "MEDICAL", "A person is reported unconscious or not responding."),
    ("SUSPECTED_BONE_INJURY", "Suspected bone injury", "MEDICAL", "A reported broken bone (not a medical assessment)."),
    ("BURN_INJURY", "Burn", "MEDICAL", "A person is reported to be burned."),
    ("HEAT_ILLNESS", "Heat illness", "MEDICAL", "A person is reported unwell from heat."),
    ("SPORTS_INJURY", "Sports injury", "MEDICAL", "A person was hurt during sport."),
    ("GENERAL_INJURY", "Injury (general)", "MEDICAL", "A reported injury that matches no more specific type."),
    ("CHEMICAL_SPILL", "Chemical spill", "CHEMICAL", "Spill of a chemical or hazardous material."),
    ("CHEMICAL_FUMES", "Fumes", "CHEMICAL", "Fumes or an unusual chemical smell."),
    ("GAS_LEAK", "Gas leak", "CHEMICAL", "Gas leak outside the kitchen setting."),
    ("BROKEN_CONTAINER", "Broken container", "CHEMICAL", "Broken bottle or container of material."),
    ("BATTERY_SWELLING", "Battery swelling", "CHEMICAL", "Swollen or bulging battery."),
    ("ROBOTICS_MALFUNCTION", "Robotics malfunction", "CHEMICAL", "Robot or moving machine behaving unexpectedly."),
    ("BROKEN_GLASS", "Broken glass", "PROPERTY", "Broken or shattered glass."),
    ("DAMAGED_FURNITURE", "Damaged furniture", "PROPERTY", "Broken or damaged furniture."),
    ("CEILING_DAMAGE", "Ceiling damage", "PROPERTY", "Damaged or cracked ceiling."),
    ("DOOR_LOCK_FAILURE", "Door or lock failure", "PROPERTY", "Door stuck, jammed or lock failed."),
    ("FALLING_OBJECT", "Falling object", "PROPERTY", "Object that has fallen or may fall."),
    ("SERVER_OVERHEATING", "Server overheating", "IT", "Overheating server or network equipment."),
    ("COOLING_FAILURE", "Cooling failure", "IT", "Cooling or air conditioning failure for equipment."),
    ("STORAGE_FAILURE", "Storage failure", "IT", "Storage system failure or outage."),
    ("NETWORK_OUTAGE", "Network outage", "IT", "Network or internet outage."),
    ("POWER_OUTAGE", "Power outage", "IT", "Loss of electrical power."),
    ("LPG_SMELL", "LPG smell", "FOOD", "Smell of LPG or cooking gas."),
    ("HOT_OIL_SPILL", "Hot oil spill", "FOOD", "Spill of hot or cooking oil."),
    ("REFRIGERATION_FAILURE", "Refrigeration failure", "FOOD", "Failure of cold storage or refrigeration."),
    ("FOOD_CONTAMINATION", "Suspected food contamination", "FOOD", "Food or drink that may be contaminated."),
    ("ROAD_ACCIDENT", "Road accident", "OUTDOOR_SECURITY", "Vehicle accident on campus."),
    ("FALLEN_BRANCH", "Fallen branch", "OUTDOOR_SECURITY", "Fallen tree or branch."),
    ("POOR_LIGHTING", "Poor lighting", "OUTDOOR_SECURITY", "Missing or failed lighting."),
    ("THEFT", "Theft", "OUTDOOR_SECURITY", "Reported theft."),
    ("ALTERCATION", "Altercation", "OUTDOOR_SECURITY", "Argument or dispute between people."),
    ("TRAPPED_PERSON", "Trapped person", "OUTDOOR_SECURITY", "Person trapped in a lift, room or similar space."),
    ("BLOCKED_EXIT", "Blocked exit", "OUTDOOR_SECURITY", "Blocked or obstructed exit or escape route."),
]
TYPE_CODES = [t[0] for t in INCIDENT_TYPES]
for code, name, cat, desc in INCIDENT_TYPES:
    node("incident-type:" + slug(code), "INCIDENT_TYPE", name, {"code": code, "category": cat, "description": desc})

LOCATION_TYPES = [
    ("CLASSROOM", "Classroom", "ACADEMIC"), ("SEMINAR_ROOM", "Seminar room", "ACADEMIC"), ("AUDITORIUM", "Auditorium", "ACADEMIC"),
    ("CORRIDOR", "Corridor", "ACADEMIC"), ("STAIRS", "Stairs", "ACADEMIC"), ("LIFT", "Lift", "ACADEMIC"),
    ("COMPUTER_LAB", "Computer lab", "TECHNICAL"), ("RESEARCH_LAB", "Research lab", "TECHNICAL"),
    ("ELECTRONICS_LAB", "Electronics lab", "TECHNICAL"), ("ROBOTICS_SPACE", "Robotics space", "TECHNICAL"),
    ("SERVER_ROOM", "Server room", "TECHNICAL"), ("NETWORK_ROOM", "Network room", "TECHNICAL"),
    ("ELECTRICAL_ROOM", "Electrical room", "TECHNICAL"),
    ("HOSTEL_ROOM", "Hostel room", "RESIDENTIAL"), ("HOSTEL_CORRIDOR", "Hostel corridor or stairs", "RESIDENTIAL"),
    ("HOSTEL_COMMON", "Hostel common area", "RESIDENTIAL"), ("HOSTEL_WASHROOM", "Hostel washroom", "RESIDENTIAL"),
    ("WASHROOM", "Washroom", "PUBLIC"), ("PANTRY", "Pantry", "RESIDENTIAL"),
    ("KITCHEN", "Kitchen", "FOOD"), ("DINING_AREA", "Dining area or mess hall", "FOOD"), ("CANTEEN", "Canteen", "FOOD"),
    ("FOOD_STORE", "Food store", "FOOD"), ("COLD_STORAGE", "Cold storage", "FOOD"),
    ("HEALTH_FACILITY", "Campus health facility", "HEALTH_SUPPORT"), ("SUPPORT_OFFICE", "Security, facilities or IT support office", "HEALTH_SUPPORT"),
    ("LIBRARY", "Library", "PUBLIC"), ("SHOP", "Shop", "PUBLIC"), ("ATM", "ATM", "PUBLIC"), ("LAUNDRY", "Laundry", "PUBLIC"),
    ("RECEPTION", "Reception", "PUBLIC"),
    ("FOOTBALL_GROUND", "Football ground", "OUTDOOR"), ("SPORTS_COURT", "Sports court", "OUTDOOR"), ("ROAD", "Road", "OUTDOOR"),
    ("FOOTPATH", "Footpath", "OUTDOOR"), ("PARKING", "Parking", "OUTDOOR"), ("OPEN_GROUND", "Open ground", "OUTDOOR"),
]
for code, name, cat in LOCATION_TYPES:
    node("location-type:" + slug(code), "LOCATION_TYPE", name, {"code": code, "category": cat})

ASSET_TYPES = [("DESKTOP", "Desktop or workstation"), ("LAPTOP", "Laptop"), ("SERVER", "Server"),
               ("NETWORK_EQUIPMENT", "Network equipment"), ("STORAGE_SYSTEM", "Storage system"), ("COOLING_UNIT", "Cooling unit"),
               ("UPS", "Power backup unit"), ("POWER_STRIP", "Power strip or extension board"), ("SOCKET", "Socket or switch"),
               ("WIRING", "Wiring or cable"), ("ELECTRICAL_PANEL", "Electrical panel"), ("COOKING_EQUIPMENT", "Cooking equipment"),
               ("LPG_CYLINDER", "LPG cylinder"), ("REFRIGERATION", "Refrigeration equipment"), ("FURNITURE", "Furniture"),
               ("DOOR", "Door or lock"), ("FIRE_EXIT", "Fire exit"), ("WINDOW_GLASS", "Window or glass"), ("CEILING", "Ceiling"),
               ("PIPE", "Pipe or tap"), ("DRAIN", "Drain"), ("BATTERY", "Battery"), ("LIGHTING", "Lighting"),
               ("CHEMICAL_CONTAINER", "Chemical container"), ("SPORTS_EQUIPMENT", "Sports equipment")]
for code, name in ASSET_TYPES:
    node("asset-type:" + slug(code), "ASSET_TYPE", name, {"code": code})

HAZARDS = [  # code, class, activeRisk
    ("SMOKE", "FIRE", True), ("FIRE", "FIRE", True), ("ELECTRICAL_DAMAGE", "ELECTRICAL", False),
    ("ELECTRIC_SHOCK_RISK", "ELECTRICAL", True), ("WATER_NEAR_POWER", "ELECTRICAL", True), ("SLIP", "PHYSICAL", True),
    ("WATER_ACCUMULATION", "WATER", True), ("GAS_LEAK", "CHEMICAL", True), ("CHEMICAL_EXPOSURE", "CHEMICAL", True),
    ("FUMES", "CHEMICAL", True), ("BLOCKED_EXIT", "PHYSICAL", True), ("ACCESS_PROBLEM", "PHYSICAL", False),
    ("OVERHEATING", "THERMAL", True), ("FALLING_OBJECT", "PHYSICAL", True), ("SHARP_EDGES", "PHYSICAL", True),
    ("STRUCTURAL_DAMAGE", "STRUCTURAL", False), ("UNSTABLE_FURNITURE", "PHYSICAL", True), ("BATTERY_HAZARD", "THERMAL", True),
    ("CONTAMINATION", "ENVIRONMENTAL", True), ("HOT_OIL", "THERMAL", True), ("TRAFFIC", "PHYSICAL", True),
    ("LOW_VISIBILITY", "ENVIRONMENTAL", True), ("PERSONAL_SAFETY", "SECURITY", True), ("PERSON_TRAPPED", "PHYSICAL", True),
    ("MOVING_MACHINERY", "PHYSICAL", True), ("INJURED_PERSON", "MEDICAL", True), ("DATA_LOSS_RISK", "DATA", False),
    ("SERVICE_DISRUPTION", "OPERATIONAL", False),
]
for code, hc, risk in HAZARDS:
    node("hazard:" + slug(code), "HAZARD", code.replace("_", " ").capitalize(), {"code": code, "hazardClass": hc, "activeRisk": risk})

CONSEQUENCES = [  # slug, name, type, impactClass, text, observedWhenEffect
    ("smoke-spread", "Smoke spread", "SECONDARY", "SMOKE_SPREAD", "Smoke may spread to nearby rooms.", None),
    ("smoke-exposure", "Exposure to smoke", "SAFETY", "EXPOSURE", "People nearby may be exposed to smoke or fumes.", None),
    ("fire-spread", "Fire spread", "SECONDARY", "FIRE_SPREAD", "The fire could spread to nearby areas.", None),
    ("structural-damage", "Structural damage", "ASSET", "STRUCTURAL_DAMAGE", "The structure of the area may be damaged.", None),
    ("equipment-damage", "Equipment damage", "ASSET", "EQUIPMENT_DAMAGE", "Nearby equipment may be damaged.", None),
    ("room-closure", "Room closure", "OPERATIONAL", "ROOM_CLOSURE", "The room may need to be closed until it is cleared.", "ROOM_UNUSABLE"),
    ("workstation-unavailable", "Workstations unavailable", "OPERATIONAL", "WORKSTATION_LOSS", "Affected workstations may be unavailable.", "WORKSTATION_UNAVAILABLE"),
    ("data-loss", "Data loss", "ASSET", "DATA_LOSS", "Data stored on the affected equipment could be lost.", None),
    ("service-outage", "Service outage", "OPERATIONAL", "SERVICE_OUTAGE", "Services that depend on the affected equipment may be interrupted.", "SERVICE_OUTAGE"),
    ("thermal-failure", "Overheating failure", "SECONDARY", "REPEATED_FAILURE", "Overheating may lead to equipment failure or fire.", None),
    ("shock-exposure", "Electrical exposure", "SAFETY", "EXPOSURE", "People nearby could be exposed to an electrical hazard.", None),
    ("water-reaches-power", "Water reaching electricity", "SECONDARY", "WATER_ELECTRICITY", "Water may reach electrical equipment.", None),
    ("slip-harm", "Slip risk", "SAFETY", "FALL_RISK", "People could slip on wet surfaces.", None),
    ("gas-buildup", "Gas build up", "SAFETY", "EXPOSURE", "Gas could build up and become an ignition risk.", None),
    ("chem-exposure", "Exposure to material", "SAFETY", "EXPOSURE", "People nearby may be exposed to hazardous material.", None),
    ("contamination-spread", "Spread of material", "SECONDARY", "CONTAMINATION", "The material may spread to adjoining areas.", None),
    ("harm-nearby", "Harm to people nearby", "SAFETY", "INJURY_RISK", "People nearby could be hurt.", None),
    ("reduced-capacity", "Reduced capacity", "OPERATIONAL", "REDUCED_CAPACITY", "Available room capacity may be reduced.", None),
    ("food-unsafe", "Unsafe food or drink", "SAFETY", "FOOD_SAFETY", "Affected food or drink may not be safe to consume.", None),
    ("blocked-route", "Blocked route", "SAFETY", "BLOCKED_EVACUATION", "Evacuation routes could be blocked or reduced.", None),
    ("medical-attention", "Need for medical attention", "SAFETY", "INJURY_RISK", "The person may need qualified medical attention.", None),
    ("poor-visibility", "Poor visibility", "SAFETY", "INJURY_RISK", "People could trip or be hurt where visibility is poor.", None),
    ("trapped-risk", "Person trapped", "SAFETY", "ENTRAPMENT", "The trapped person may need responders to reach them safely.", None),
    ("repeated-failure", "Repeated failure", "SECONDARY", "REPEATED_FAILURE", "The problem may recur if the underlying cause is not addressed.", None),
]
for s, name, ctype, impact, text, obs in CONSEQUENCES:
    p = {"consequenceType": ctype, "impactClass": impact, "text": text}
    if obs:
        p["observedWhenEffect"] = obs
    node("consequence:" + s, "CONSEQUENCE", name, p)

# ---------------------------------------------------------------------------------------------------------------- actions
ACTIONS = {}  # slug -> dict
ACTION_ORDER = []


def act(s, title, phase, user, prio, text, key, order, team, medical=None, forb=None, human=False, src=DEMO, draft=False):
    assert s not in ACTIONS, s
    ACTIONS[s] = dict(slug=s, title=title, phase=phase, user=user, prio=prio, text=text, key=key, order=order, team=team,
                      medical=medical, forb=forb, human=human, src=src, draft=draft)
    ACTION_ORDER.append(s)


LS, HI, RE, SC, EP, RS, PV = ("LIFE_SAFETY", "HAZARD_ISOLATION", "RESPONDER_ESCALATION", "SERVICE_CONTINUITY",
                              "EVIDENCE_PRESERVATION", "RESTORATION", "PREVENTION")
I, O, R, P = "IMMEDIATE", "OPERATIONAL", "RECOVERY", "PREVENTION"
# --- self-protective actions the reporter may perform (userCanPerform = true)
act("leave-area", "Move away from the area", I, True, 95, "Move away from the affected area and keep other people away from it.", "MOVE_AWAY_KEEP_OTHERS_AWAY", LS, "CAMPUS_SECURITY")
act("report-fire", "Report the fire or smoke", I, True, 96, "Report the fire or smoke to campus security or fire safety staff and follow their instructions.", "REPORT_TO_FIRE_SAFETY", RE, "FIRE_SAFETY")
act("follow-evac", "Follow evacuation instructions", I, True, 92, "Follow posted evacuation instructions and move to a safe place away from the building.", "FOLLOW_EVACUATION_INSTRUCTIONS", LS, "FIRE_SAFETY")
act("avoid-electrical", "Stay away from electrical equipment", I, True, 94, "Stay away from the affected electrical equipment and do not touch it.", "AVOID_ELECTRICAL_EQUIPMENT", HI, "ELECTRICAL_MAINTENANCE", forb=["TOUCH_ELECTRICAL_EQUIPMENT"])
act("exclusion-zone-water-power", "Keep clear of the wet area near power", I, True, 97, "Stay away from the wet area near the power equipment and keep other people away from it.", "EXCLUSION_ZONE_WATER_POWER", LS, "ELECTRICAL_MAINTENANCE", forb=["TOUCH_ELECTRICAL_EQUIPMENT"])
act("report-electrical", "Report the electrical problem", I, True, 90, "Report the electrical problem to electrical maintenance.", "REPORT_TO_ELECTRICAL", RE, "ELECTRICAL_MAINTENANCE")
act("report-leak", "Report the water leak", I, True, 88, "Report the water leak to plumbing or facilities staff.", "REPORT_WATER_LEAK", RE, "PLUMBING")
act("report-drain", "Report the blocked drain", I, True, 85, "Report the blocked drain to plumbing or facilities staff.", "REPORT_BLOCKED_DRAIN", RE, "PLUMBING")
act("warn-slip", "Warn others about the wet floor", I, True, 89, "Stay away from the wet floor and warn other people about the slip risk.", "WARN_SLIP_RISK", LS, "FACILITIES")
act("report-gas", "Report the gas smell", I, True, 97, "Move away from the gas smell and report it immediately to fire safety or facilities staff.", "REPORT_GAS_SMELL", RE, "FIRE_SAFETY")
act("no-ignition", "Avoid flames and switches", I, True, 96, "Do not use open flames, switches or electrical devices near the gas smell.", "AVOID_IGNITION_SOURCES", HI, "FIRE_SAFETY", forb=["OPERATE_GAS_SUPPLY"])
act("avoid-chemical", "Stay away from the spilled material", I, True, 96, "Stay away from the spilled material, keep other people away and do not touch it.", "AVOID_CHEMICAL_MATERIAL", LS, "LAB_SAFETY", forb=["CLEAN_UP_SPILL", "HANDLE_CHEMICALS"])
act("report-chemical", "Report the spill", I, True, 95, "Report the spill to lab safety staff and wait for specialist responders.", "REPORT_CHEMICAL_INCIDENT", RE, "LAB_SAFETY")
act("report-battery", "Report the swollen battery", I, True, 94, "Report the swollen battery to lab safety staff and wait for their instructions.", "REPORT_BATTERY_HAZARD", RE, "LAB_SAFETY")
act("avoid-battery", "Stay away from the swollen battery", I, True, 96, "Stay away from the swollen battery, keep other people away and do not touch it.", "AVOID_BATTERY", LS, "LAB_SAFETY")
act("avoid-robot", "Stay away from the robot", I, True, 94, "Stay away from the malfunctioning robot and keep other people away until authorized lab staff clear it.", "AVOID_ROBOT", LS, "LAB_SAFETY")
act("report-robot", "Report the robot malfunction", I, True, 90, "Report the robot malfunction to lab safety staff.", "REPORT_ROBOT_MALFUNCTION", RE, "LAB_SAFETY")
act("medical-contact", "Contact medical responders", I, True, 99, "Contact campus medical services or qualified emergency responders and follow their instructions.", "CONTACT_MEDICAL_RESPONDERS", LS, "MEDICAL_SERVICES", medical="CONTACT_RESPONDERS")
act("stay-for-responders", "Wait for qualified responders", I, True, 90, "Stay nearby and follow the instructions of qualified responders when they arrive.", "WAIT_FOR_RESPONDERS", LS, "MEDICAL_SERVICES", medical="FOLLOW_RESPONDER")
act("leave-assessment", "Leave assessment to responders", I, True, 88, "Leave the assessment of the person to qualified responders.", "LEAVE_ASSESSMENT_TO_RESPONDERS", LS, "MEDICAL_SERVICES", medical="FOLLOW_RESPONDER", forb=["MEDICAL_ASSESSMENT_BY_REPORTER"])
act("stop-play", "Stop play", I, True, 99, "Stop play and keep other people away from the injured person.", "STOP_PLAY", LS, "SPORTS_FACILITIES")
act("report-blocked-exit", "Report the blocked exit", I, True, 96, "Do not use the blocked exit, use another safe exit and report the blockage to facilities or security.", "REPORT_BLOCKED_EXIT", RE, "FACILITIES")
act("avoid-glass", "Stay away from broken glass", I, True, 94, "Stay away from the broken glass and keep other people away from it.", "AVOID_BROKEN_GLASS", LS, "FACILITIES")
act("avoid-overhead", "Stay clear of overhead damage", I, True, 94, "Stay away from the area below the damaged ceiling or hanging object and keep other people away.", "AVOID_OVERHEAD_HAZARD", LS, "FACILITIES")
act("stop-using-furniture", "Stop using damaged furniture", I, True, 90, "Stop using the damaged furniture and keep other people from using it.", "STOP_USING_FURNITURE", LS, "FACILITIES")
act("report-damage", "Report the damage", I, True, 88, "Report the damage to facilities staff.", "REPORT_DAMAGE_TO_FACILITIES", RE, "FACILITIES")
act("report-door", "Report the door fault", I, True, 88, "Report the door or lock fault to facilities staff and use another safe route if the door is blocked.", "REPORT_DOOR_FAULT", RE, "FACILITIES")
act("avoid-hot-spill", "Stay away from the hot spill", I, True, 94, "Stay away from the hot oil spill and keep other people away from it.", "AVOID_HOT_SPILL", LS, "MESS_MANAGEMENT")
act("report-food-area", "Report to mess management", I, True, 88, "Report the problem to mess management.", "REPORT_FOOD_AREA_INCIDENT", RE, "MESS_MANAGEMENT")
act("avoid-food", "Avoid the affected food", I, True, 92, "Do not consume the affected food or drink and report it to mess management.", "AVOID_AFFECTED_FOOD", LS, "MESS_MANAGEMENT")
act("avoid-water-source", "Avoid the affected water", I, True, 92, "Do not use the affected water source for drinking and report it to facilities staff.", "AVOID_WATER_SOURCE", LS, "FACILITIES")
act("move-from-traffic", "Move away from traffic", I, True, 98, "Move away from moving traffic to a safe place and contact campus security or emergency responders.", "MOVE_FROM_TRAFFIC_AND_CALL", LS, "CAMPUS_SECURITY")
act("avoid-branch", "Stay away from the fallen branch", I, True, 90, "Stay away from the fallen branch and keep other people away from it.", "AVOID_FALLEN_BRANCH", LS, "FACILITIES")
act("report-lighting", "Report the poor lighting", I, True, 80, "Report the poor lighting to facilities staff and use a well lit route.", "REPORT_POOR_LIGHTING", RE, "FACILITIES")
act("report-theft", "Report the theft", I, True, 90, "Report the theft to campus security and do not confront anyone.", "REPORT_THEFT", RE, "CAMPUS_SECURITY")
act("report-altercation", "Move away and report", I, True, 95, "Move away from the altercation and report it to campus security.", "REPORT_ALTERCATION", RE, "CAMPUS_SECURITY")
act("report-trapped", "Report the trapped person", I, True, 98, "Contact campus security or emergency responders about the trapped person and do not try to force doors or equipment.", "REPORT_TRAPPED_PERSON", RE, "CAMPUS_SECURITY")
act("report-infra", "Report the problem to IT operations", I, True, 92, "Report the problem to IT operations so that infrastructure staff can respond.", "ESCALATE_TO_IT_OPERATIONS", RE, "IT_OPERATIONS")
act("keep-damaged-unused", "Keep damaged equipment unused", O, True, 86, "Do not use the damaged equipment until authorized staff have cleared it.", "KEEP_DAMAGED_EQUIPMENT_UNUSED", HI, "IT_OPERATIONS", forb=["POWER_ON_DAMAGED_DEVICE"])
act("avoid-storage-writes", "Avoid saving to affected storage", O, True, 80, "Avoid saving new data to the affected storage until authorized IT staff confirm it is working.", "AVOID_AFFECTED_STORAGE", SC, "IT_OPERATIONS")
# --- authorized operational / recovery / prevention actions (userCanPerform = false)
A = lambda *a, **k: act(*a[:3], False, *a[3:], **k)
A("close-area", "Keep the area closed", O, 88, "Ask authorized facilities staff to keep the affected room closed until it is cleared.", "CLOSE_AREA_PENDING_INSPECTION", HI, "FACILITIES")
A("hostel-close-area", "Keep the affected room closed", O, 88, "Ask authorized hostel administration to keep the affected room closed until it is cleared.", "CLOSE_AREA_PENDING_INSPECTION", HI, "HOSTEL_ADMIN")
A("isolate-power", "Isolate power to the area", O, 90, "Ask authorized electrical maintenance to isolate power to the affected area.", "ISOLATE_POWER_AFFECTED_AREA", HI, "ELECTRICAL_MAINTENANCE", forb=["OPERATE_UTILITY_SHUTOFF"])
A("stop-water-source", "Stop the water source", O, 85, "Ask authorized plumbing staff to stop the water source once the area is safe.", "STOP_WATER_SOURCE", HI, "PLUMBING", forb=["OPERATE_UTILITY_SHUTOFF"])
A("isolate-asset", "Isolate the affected equipment", O, 91, "Ask authorized IT staff to isolate the affected equipment from power and the network.", "ISOLATE_AFFECTED_ASSET", HI, "IT_OPERATIONS", forb=["POWER_ON_DAMAGED_DEVICE"])
A("fire-inspection", "Fire safety inspection", R, 85, "Ask authorized fire safety staff to inspect the area and confirm it is safe.", "FIRE_SAFETY_INSPECTION", RS, "FIRE_SAFETY")
A("electrical-inspection", "Electrical inspection", R, 84, "Ask authorized electrical maintenance to inspect the wiring and equipment before the area is used again.", "ELECTRICAL_INSPECTION", RS, "ELECTRICAL_MAINTENANCE")
A("reopen-clearance", "Clearance before reopening", R, 80, "Ask authorized facilities staff to confirm clearance before the room is reopened for use.", "REOPEN_AFTER_CLEARANCE", RS, "FACILITIES")
A("inspect-affected-device", "Inspect the affected device", R, 83, "Ask authorized IT staff and electrical maintenance to inspect the affected device and its power supply before it is used again.", "INSPECT_AFFECTED_DEVICE", RS, "IT_OPERATIONS")
A("preserve-logs-recovery", "Preserve logs and backups", R, 82, "Ask authorized IT staff to preserve logs and available backups before restoration.", "PRESERVE_LOGS_BACKUPS", EP, "IT_OPERATIONS", forb=["POWER_ON_DAMAGED_DEVICE", "CHANGE_PRODUCTION_RETENTION_DIRECTLY"], human=True)
A("preserve-logs-ops", "Preserve logs and backups", O, 86, "Ask authorized IT staff to preserve logs and available backups before restoration.", "PRESERVE_LOGS_BACKUPS", EP, "IT_OPERATIONS", forb=["CHANGE_PRODUCTION_RETENTION_DIRECTLY"], human=True)
A("replacement-workstation", "Replacement workstation", R, 70, "Ask authorized IT staff to arrange a replacement workstation for affected users.", "REPLACEMENT_WORKSTATION", RS, "IT_OPERATIONS")
A("assess-service-impact", "Assess service impact", O, 90, "Ask authorized IT operations to identify which services depend on the affected equipment.", "ASSESS_SERVICE_IMPACT", SC, "IT_OPERATIONS")
A("invoke-continuity", "Start the continuity plan", O, 85, "Ask authorized IT operations to start the service continuity plan for affected services.", "INVOKE_CONTINUITY_PLAN", SC, "IT_OPERATIONS", human=True)
A("inspect-cooling", "Inspect cooling equipment", O, 84, "Ask authorized facilities staff to inspect the cooling equipment.", "INSPECT_COOLING_EQUIPMENT", RS, "FACILITIES")
A("verify-cooling", "Verify cooling", R, 80, "Ask authorized IT operations to verify that cooling is working normally before normal service resumes.", "VERIFY_COOLING", RS, "IT_OPERATIONS")
A("verify-restoration", "Verify service restoration", R, 82, "Ask authorized IT operations to verify that the affected services work normally after restoration.", "VERIFY_SERVICE_RESTORATION", RS, "IT_OPERATIONS")
A("verify-data-integrity", "Verify data integrity", R, 78, "Ask authorized IT staff to verify the integrity of data and backups after restoration.", "VERIFY_DATA_INTEGRITY", RS, "IT_OPERATIONS")
A("inspect-power-supply", "Inspect the power supply", O, 80, "Ask authorized electrical maintenance to inspect the power supply to the affected area.", "INSPECT_POWER_SUPPLY", RS, "ELECTRICAL_MAINTENANCE")
A("inspect-ups", "Inspect the power backup unit", O, 85, "Ask authorized electrical maintenance to inspect the power backup unit before it is used again.", "INSPECT_UPS", HI, "ELECTRICAL_MAINTENANCE", forb=["POWER_ON_DAMAGED_DEVICE"])
A("review-temp-monitoring", "Review temperature monitoring", P, 60, "Ask authorized IT operations to review temperature monitoring and alerts for the equipment room.", "REVIEW_TEMPERATURE_MONITORING", PV, "IT_OPERATIONS")
A("review-redundancy", "Review redundancy", P, 58, "Ask authorized IT operations to review redundancy and monitoring for the affected service.", "REVIEW_REDUNDANCY", PV, "IT_OPERATIONS")
A("repair-leak", "Repair the leak", O, 88, "Ask authorized plumbing staff to repair the leak.", "REPAIR_LEAK", RS, "PLUMBING")
A("clear-drain", "Clear the blocked drain", O, 86, "Ask authorized plumbing staff to clear the blocked drain.", "CLEAR_BLOCKED_DRAIN", RS, "PLUMBING")
A("restrict-wet-area", "Restrict the wet area", O, 87, "Ask authorized facilities staff to place warning signs and limit use of the wet area until it is safe.", "RESTRICT_WET_AREA", HI, "FACILITIES")
A("inspect-adjoining", "Inspect adjoining areas", O, 80, "Ask authorized plumbing staff to inspect adjoining areas for moisture or damage.", "INSPECT_ADJOINING_AREAS", RS, "PLUMBING")
A("confirm-repair", "Confirm the repair", R, 75, "Ask authorized plumbing staff to confirm the repair before normal use resumes.", "CONFIRM_REPAIR_BEFORE_USE", RS, "PLUMBING")
A("inspect-shared-plumbing", "Inspect the shared pipe path", P, 85, "Ask authorized plumbing staff to inspect the shared supply and drain path instead of only repeating fixture level repair.", "INSPECT_SHARED_PLUMBING", PV, "PLUMBING")
A("plumbing-electrical-clearance", "Plumbing and electrical clearance", R, 84, "Ask authorized plumbing and electrical staff to confirm the area is dry and safe before equipment is used again.", "PLUMBING_ELECTRICAL_CLEARANCE", RS, "PLUMBING")
A("inspect-water-source", "Inspect the water source", O, 88, "Ask authorized facilities staff to inspect the water source and confirm it is safe before use resumes.", "INSPECT_WATER_SOURCE", RS, "FACILITIES")
A("review-water-checks", "Review water checks", P, 55, "Ask authorized facilities staff to review routine checks for drinking water sources.", "REVIEW_WATER_CHECKS", PV, "FACILITIES")
A("inspect-gas-supply", "Inspect the gas supply", O, 92, "Ask authorized fire safety or facilities staff to inspect the gas supply and confirm it is safe.", "INSPECT_GAS_SUPPLY", HI, "FACILITIES", forb=["OPERATE_GAS_SUPPLY"])
A("suspend-service", "Suspend food service", O, 88, "Ask authorized mess management to suspend cooking service in the affected area until it is cleared.", "SUSPEND_SERVICE_UNTIL_CLEARANCE", SC, "MESS_MANAGEMENT")
A("service-clearance", "Clearance before service resumes", R, 82, "Ask authorized staff to confirm clearance before cooking or food service resumes.", "CLEARANCE_BEFORE_SERVICE_RESUMES", RS, "FIRE_SAFETY")
A("review-gas-checks", "Review gas checks", P, 60, "Ask authorized fire safety staff to review gas supply checks in food areas.", "REVIEW_GAS_CHECKS", PV, "FIRE_SAFETY")
A("restrict-food-area", "Restrict the food area", O, 85, "Ask authorized mess management to keep the affected area restricted until it is safe.", "RESTRICT_FOOD_AREA", HI, "MESS_MANAGEMENT")
A("check-affected-food", "Check the affected food", O, 88, "Ask authorized mess management to check stored food affected by the problem before it is served.", "CHECK_AFFECTED_FOOD", SC, "MESS_MANAGEMENT")
A("inspect-refrigeration", "Inspect refrigeration equipment", O, 84, "Ask authorized facilities staff to inspect the refrigeration equipment.", "INSPECT_REFRIGERATION", RS, "FACILITIES")
A("review-food-checks", "Review food handling checks", P, 55, "Ask authorized mess management to review food handling and storage checks.", "REVIEW_FOOD_CHECKS", PV, "MESS_MANAGEMENT")
A("isolate-chemical-area", "Isolate the area", O, 92, "Ask authorized lab safety staff to isolate the affected area.", "ISOLATE_CHEMICAL_AREA", HI, "LAB_SAFETY", forb=["CLEAN_UP_SPILL", "HANDLE_CHEMICALS"])
A("specialist-chemical", "Arrange specialist responders", O, 90, "Ask authorized lab safety staff to arrange specialist responders for the spill.", "ARRANGE_SPECIALIST_RESPONSE", RE, "LAB_SAFETY", forb=["CLEAN_UP_SPILL"])
A("isolate-battery-hazard", "Isolate the battery hazard", O, 90, "Ask authorized lab safety staff to remove the battery from service and isolate the area.", "ISOLATE_BATTERY_HAZARD", HI, "LAB_SAFETY")
A("inspect-robot", "Inspect the robotic equipment", O, 85, "Ask authorized lab safety staff to inspect the robotic equipment before it is used again.", "INSPECT_ROBOTIC_EQUIPMENT", RS, "LAB_SAFETY")
A("chemical-clearance", "Clearance before reuse", R, 82, "Ask authorized lab safety staff to confirm clearance before the area is used again.", "CLEARANCE_BEFORE_REUSE", RS, "LAB_SAFETY")
A("review-chemical-storage", "Review storage and labelling", P, 60, "Ask authorized lab safety staff to review storage and labelling for similar materials.", "REVIEW_CHEMICAL_STORAGE", PV, "LAB_SAFETY")
A("remove-exit-obstruction", "Clear the exit route", O, 92, "Ask authorized facilities staff to remove the obstruction from the exit route.", "REMOVE_EXIT_OBSTRUCTION", HI, "FACILITIES")
A("inspect-similar-routes", "Inspect similar exit routes", O, 80, "Ask authorized facilities staff to inspect similar exit routes in the building.", "INSPECT_SIMILAR_ROUTES", RS, "FACILITIES")
A("compliance-follow-up", "Compliance follow up", P, 75, "Ask authorized fire safety staff to record a compliance follow up for the exit route.", "COMPLIANCE_FOLLOW_UP", PV, "FIRE_SAFETY")
A("remove-from-use", "Remove damaged furniture from use", O, 88, "Ask authorized facilities staff to remove the damaged furniture from use.", "REMOVE_FROM_USE", HI, "FACILITIES")
A("inspect-similar-items", "Inspect similar items", O, 80, "Ask authorized facilities staff to inspect similar items in the room.", "INSPECT_SIMILAR_ITEMS", RS, "FACILITIES")
A("update-capacity", "Update available capacity", O, 75, "Ask the academic office to update the available capacity for the room.", "UPDATE_AVAILABLE_CAPACITY", SC, "ACADEMIC_OFFICE")
A("schedule-furniture-checks", "Schedule furniture checks", P, 55, "Ask authorized facilities staff to schedule regular furniture checks.", "SCHEDULE_FURNITURE_CHECKS", PV, "FACILITIES")
A("remove-broken-glass", "Secure and remove broken glass", O, 88, "Ask authorized facilities staff to secure the area and remove the broken glass.", "REMOVE_BROKEN_GLASS", HI, "FACILITIES")
A("inspect-structure", "Inspect the structure", O, 88, "Ask authorized facilities staff to inspect the ceiling and structure of the area.", "INSPECT_STRUCTURE", RS, "FACILITIES")
A("repair-door", "Repair the door or lock", O, 85, "Ask authorized facilities staff to repair the door or lock.", "REPAIR_DOOR_OR_LOCK", RS, "FACILITIES")
A("inspect-incident-location", "Inspect the location", O, 65, "Ask authorized facilities staff to inspect the location and equipment involved.", "INSPECT_INCIDENT_LOCATION", RS, "FACILITIES")
A("review-recurring-safety", "Review recurring safety issues", P, 55, "Ask authorized facilities staff to review the location for recurring safety issues.", "REVIEW_RECURRING_SAFETY", PV, "FACILITIES")
A("inspect-playing-area", "Inspect the playing area", O, 70, "Ask authorized sports facility staff to inspect the playing area and equipment involved.", "INSPECT_PLAYING_AREA", RS, "SPORTS_FACILITIES")
A("review-sports-safety", "Review sports safety", P, 60, "Ask authorized sports facility staff to review field condition and safety equipment checks.", "REVIEW_SPORTS_SAFETY", PV, "SPORTS_FACILITIES")
A("secure-accident-location", "Secure the location", O, 85, "Ask authorized campus security to secure the location and manage traffic.", "SECURE_ACCIDENT_LOCATION", HI, "CAMPUS_SECURITY")
A("review-road-safety", "Review road safety", P, 55, "Ask authorized facilities staff to review road safety at the location.", "REVIEW_ROAD_SAFETY", PV, "FACILITIES")
A("remove-branch", "Remove the fallen branch", O, 85, "Ask authorized facilities staff to remove the fallen branch.", "REMOVE_FALLEN_BRANCH", HI, "FACILITIES")
A("inspect-nearby-trees", "Inspect nearby trees", P, 55, "Ask authorized facilities staff to inspect nearby trees.", "INSPECT_NEARBY_TREES", PV, "FACILITIES")
A("repair-lighting", "Repair the lighting", O, 80, "Ask authorized facilities staff to repair the lighting.", "REPAIR_LIGHTING", RS, "FACILITIES")
A("preserve-evidence-security", "Record the report", O, 80, "Ask authorized campus security to record the report and preserve any evidence.", "PRESERVE_EVIDENCE_SECURITY", EP, "CAMPUS_SECURITY")
A("review-location-security", "Review location security", P, 55, "Ask authorized campus security to review security at the location.", "REVIEW_LOCATION_SECURITY", PV, "CAMPUS_SECURITY")
A("attend-location", "Attend the location", O, 88, "Ask authorized campus security to attend the location and keep the area calm.", "ATTEND_LOCATION", RE, "CAMPUS_SECURITY")
A("help-trapped", "Help the trapped person", O, 95, "Ask authorized responders to help the trapped person safely.", "HELP_TRAPPED_PERSON", RE, "CAMPUS_SECURITY")
A("inspect-involved-equipment", "Inspect the equipment involved", O, 75, "Ask authorized facilities staff to inspect the door or equipment involved.", "INSPECT_EQUIPMENT_INVOLVED", RS, "FACILITIES")
# history-driven preventive actions
A("review-power-supply-workstations", "Review workstation power supply", P, 80, "Ask authorized IT and electrical staff to review the power supply and equipment used for the affected workstations.", "REVIEW_POWER_SUPPLY_WORKSTATIONS", PV, "ELECTRICAL_MAINTENANCE")
A("review-ups-health", "Review power backup health", P, 80, "Ask authorized electrical maintenance to review the condition and service history of the power backup equipment.", "REVIEW_UPS_HEALTH", PV, "ELECTRICAL_MAINTENANCE")
A("review-common-cause-outages", "Review shared outage causes", P, 80, "Ask authorized IT operations and electrical maintenance to review whether the outages share a common cause.", "REVIEW_COMMON_CAUSE_OUTAGES", PV, "IT_OPERATIONS")
A("review-cooling-capacity", "Review cooling capacity", P, 80, "Ask authorized facilities staff to review cooling capacity for the building.", "REVIEW_COOLING_CAPACITY", PV, "FACILITIES")
A("review-electrical-load", "Review electrical load", P, 62, "Ask authorized electrical maintenance to review the electrical load on the affected circuit.", "REVIEW_ELECTRICAL_LOAD", PV, "ELECTRICAL_MAINTENANCE")
A("review-fire-checks", "Review fire safety checks", P, 60, "Ask authorized fire safety staff to review fire safety checks for similar rooms.", "REVIEW_SIMILAR_ROOMS_FIRE", PV, "FIRE_SAFETY")
# --- DRAFT recommendations that need a specific real SOP (admin preview only)
A("follow-fire-evac-sop", "Follow the approved fire evacuation procedure", O, 93, "Ask authorized fire safety staff to follow the approved fire evacuation procedure.", "FOLLOW_FIRE_EVACUATION_SOP", RE, "FIRE_SAFETY", src="sop:fire-safety:v1", draft=True, human=True)
A("follow-electrical-sop", "Follow the approved electrical isolation procedure", O, 91, "Ask authorized electrical maintenance to follow the approved electrical isolation procedure.", "FOLLOW_ELECTRICAL_SOP", HI, "ELECTRICAL_MAINTENANCE", src="sop:electrical-safety:v1", draft=True, human=True)
A("follow-chemical-sop", "Follow the approved chemical procedure", O, 91, "Ask authorized responders to follow the approved chemical specific procedure for this material.", "FOLLOW_CHEMICAL_SOP", HI, "LAB_SAFETY", src="sop:chemical-spill:v1", draft=True, human=True)
A("follow-lpg-isolation-sop", "Follow the approved LPG isolation procedure", O, 91, "Ask authorized responders to follow the approved LPG isolation procedure.", "FOLLOW_LPG_ISOLATION_SOP", HI, "FIRE_SAFETY", src="sop:lpg-safety:v1", draft=True, human=True)
A("follow-it-sop", "Follow the approved continuity procedure", O, 89, "Ask authorized IT operations to follow the approved continuity procedure for the affected service.", "FOLLOW_IT_CONTINUITY_SOP", SC, "IT_OPERATIONS", src="sop:it-continuity:v1", draft=True, human=True)
A("follow-water-sop", "Follow the approved water isolation procedure", O, 89, "Ask authorized plumbing staff to follow the approved water isolation procedure.", "FOLLOW_WATER_SOP", HI, "PLUMBING", src="sop:water-plumbing:v1", draft=True, human=True)
A("follow-sports-sop", "Follow the approved sports incident procedure", O, 89, "Ask authorized sports facility staff to follow the approved sports incident response procedure.", "FOLLOW_SPORTS_SOP", RE, "SPORTS_FACILITIES", src="sop:sports-injury:v1", draft=True, human=True)
A("follow-hostel-sop", "Follow the approved hostel safety procedure", O, 89, "Ask authorized hostel administration to follow the approved hostel safety procedure.", "FOLLOW_HOSTEL_SOP", RE, "HOSTEL_ADMIN", src="sop:hostel-safety:v1", draft=True, human=True)
A("follow-mess-sop", "Follow the approved food safety procedure", O, 89, "Ask authorized mess management to follow the approved food safety procedure.", "FOLLOW_MESS_SOP", RE, "MESS_MANAGEMENT", src="sop:mess-safety:v1", draft=True, human=True)
A("follow-compliance-sop", "Follow the approved compliance procedure", P, 74, "Ask authorized fire safety staff to follow the approved building compliance procedure.", "FOLLOW_COMPLIANCE_SOP", PV, "FIRE_SAFETY", src="sop:building-compliance:v1", draft=True, human=True)

for s in ACTION_ORDER:
    a = ACTIONS[s]
    p = {"title": a["title"], "phase": a["phase"], "userCanPerform": a["user"], "requiresAuthorization": not a["user"],
         "priority": a["prio"], "canonicalText": a["text"], "canonicalKey": a["key"], "orderClass": a["order"]}
    if a["medical"]:
        p["medicalClass"] = a["medical"]
    if a["forb"]:
        p["forbiddenForReporter"] = a["forb"]
    p["requiresHumanApproval"] = a["human"]
    node("action:" + s, "ACTION", a["title"], p)
    rp = {"actionId": "action:" + s, "title": a["title"], "phase": a["phase"], "sourceId": a["src"]}
    if a["draft"]:
        rp["approvalStatus"] = "DRAFT"
    else:
        rp.update({"approvalStatus": "APPROVED", "approvedBy": DEMO_BY, "approvedAt": DEMO_AT})
    node("rec:" + s, "RECOMMENDATION", a["title"], rp)
    edge("rec:" + s, "SUPPORTED_BY", a["src"], {}, 50, a["src"])
    edge("action:" + s, "OWNED_BY", tid(a["team"]), {}, 50, DEMO)

# ---------------------------------------------------------------------------------------------------------------- arrangements
ARRANGEMENTS = [  # slug, title, text, team, requirements
    ("alternate-room", "Alternate room request", "Ask the academic office to identify an alternate room of suitable size.", "ACADEMIC_OFFICE", {}),
    ("alternate-lab", "Alternate lab space request", "Ask the academic office to identify alternate lab space for the affected session.", "ACADEMIC_OFFICE", {}),
    ("alternate-seating", "Alternate seating request", "Ask the academic office to identify alternate seating if fewer seats are available.", "ACADEMIC_OFFICE", {}),
    ("temporary-accommodation", "Temporary accommodation request", "Ask hostel administration to identify temporary accommodation for affected residents.", "HOSTEL_ADMIN", {}),
    ("alternate-meal-service", "Alternate meal service request", "Ask mess management to identify an alternate arrangement for serving meals while the area is closed.", "MESS_MANAGEMENT", {}),
    ("temporary-workstations", "Temporary workstations request", "Ask IT support to arrange temporary workstations for affected users.", "IT_OPERATIONS", {}),
    ("interim-service-workaround", "Interim service workaround", "Ask IT operations to share an interim workaround for the affected service.", "IT_OPERATIONS", {}),
    ("alternate-washroom", "Alternate washroom request", "Ask facilities staff to point users to an alternate washroom while the affected one is restricted.", "FACILITIES", {}),
    ("verified-alternate-room", "Move to the verified alternate room", "Move the session to the verified alternate room.", "ACADEMIC_OFFICE",
     {"knownCapacity": True, "alternateLocationVerified": True}),
]
for s, title, text, team, req in ARRANGEMENTS:
    node("arrangement:" + s, "TEMPORARY_ARRANGEMENT", title, {"title": title, "text": text, "requirements": req, "ownerTeamId": tid(team)})

# ---------------------------------------------------------------------------------------------------------------- history
PATTERNS = [  # slug, scope, types, windowDays, threshold, hourWindow, description, action
    ("water-leak-zone", "ZONE", ["WATER_LEAK", "PIPE_LEAK", "CEILING_LEAK", "WASHROOM_FLOODING", "BLOCKED_DRAIN"], 60, 3, None,
     "Three or more water related incidents in the same zone within sixty days.", "inspect-shared-plumbing"),
    ("computer-fire-asset", "ASSET", ["COMPUTER_FIRE"], 180, 2, None, "Repeated computer fires on the same asset scope.", "review-power-supply-workstations"),
    ("ups-overheating-asset", "ASSET", ["UPS_OVERHEATING"], 90, 2, None, "Repeated power backup overheating on the same asset.", "review-ups-health"),
    ("electrical-location", "LOCATION", ["SPARKING_SOCKET", "OVERLOADED_EXTENSION", "EXPOSED_WIRE"], 60, 3, None,
     "Repeated electrical faults at the same location.", "review-electrical-load"),
    ("outage-time-cluster", "TIME_CLUSTER", ["POWER_OUTAGE", "NETWORK_OUTAGE", "STORAGE_FAILURE"], 30, 3, 2,
     "Several outages close together in time of day.", "review-common-cause-outages"),
    ("cooling-building", "BUILDING", ["COOLING_FAILURE", "SERVER_OVERHEATING"], 90, 2, None,
     "Repeated cooling problems in the same building.", "review-cooling-capacity"),
    ("blocked-exit-building", "BUILDING", ["BLOCKED_EXIT"], 90, 2, None, "Repeated blocked exits in the same building.", "compliance-follow-up"),
    ("sports-injury-zone", "ZONE", ["SPORTS_INJURY"], 90, 3, None, "Repeated sports injuries in the same area.", "review-sports-safety"),
]
for s, scope, types, win, thr, hw, desc, action in PATTERNS:
    p = {"scope": scope, "incidentTypes": types, "windowDays": win, "threshold": thr}
    if hw is not None:
        p["hourWindow"] = hw
    p["description"] = desc
    node("pattern:" + s, "HISTORICAL_PATTERN", desc, p)
    edge("pattern:" + s, "TRIGGERS_ACTION", "action:" + action, {}, ACTIONS[action]["prio"], DEMO)

# ---------------------------------------------------------------------------------------------------------------- wiring
TRIG_EDGES = {}  # incident type -> set(action slugs) for DEPENDS_ON self-check


def default_status(phase):
    return ALL if phase in (I, O) else (POST if phase == R else POSTU)


def trig(types, s, st=None, p=None, **cond):
    a = ACTIONS[s]
    for t in types:
        c = {}
        c["statuses"] = st if st is not None else default_status(a["phase"])
        c.update(cond)
        edge("incident-type:" + slug(t), "TRIGGERS_ACTION", "action:" + s, c, p if p is not None else a["prio"], DEMO)
        TRIG_EDGES.setdefault(t, set()).add(s)


def wire(types, *slugs):
    for s in slugs:
        trig(types, s)


FIRE_GEN = ["CLASSROOM_FIRE", "KITCHEN_FIRE", "COOKING_FIRE", "ELECTRICAL_PANEL_FIRE", "FIRE_SMOKE"]
ALL_FIRE = FIRE_GEN + ["HOSTEL_FIRE", "COMPUTER_FIRE", "SERVER_RACK_FIRE"]
wire(ALL_FIRE, "leave-area", "report-fire", "follow-evac", "follow-fire-evac-sop")
wire(FIRE_GEN, "close-area", "isolate-power", "fire-inspection", "electrical-inspection", "reopen-clearance", "review-fire-checks")
wire(["HOSTEL_FIRE"], "hostel-close-area", "isolate-power", "fire-inspection", "electrical-inspection", "reopen-clearance", "review-fire-checks", "follow-hostel-sop")
wire(["KITCHEN_FIRE", "COOKING_FIRE"], "suspend-service", "follow-mess-sop")
wire(["ELECTRICAL_PANEL_FIRE"], "review-electrical-load", "follow-electrical-sop")
wire(["COMPUTER_FIRE"], "isolate-asset", "keep-damaged-unused", "inspect-affected-device", "preserve-logs-recovery",
     "replacement-workstation", "review-fire-checks", "review-electrical-load")
wire(["SERVER_RACK_FIRE"], "isolate-asset", "isolate-power", "assess-service-impact", "preserve-logs-recovery",
     "inspect-affected-device", "verify-restoration", "review-fire-checks")

ELEC = ["SPARKING_SOCKET", "EXPOSED_WIRE", "OVERLOADED_EXTENSION", "UPS_OVERHEATING", "ELECTRICAL_FAULT"]
wire(ELEC, "avoid-electrical", "report-electrical", "isolate-power", "electrical-inspection", "review-electrical-load", "follow-electrical-sop")
wire(["UPS_OVERHEATING"], "inspect-ups")
wire(["WATER_NEAR_POWER"], "exclusion-zone-water-power", "avoid-electrical", "report-electrical", "isolate-power", "stop-water-source",
     "plumbing-electrical-clearance", "electrical-inspection", "follow-electrical-sop")

WATER = ["PIPE_LEAK", "CEILING_LEAK", "WASHROOM_FLOODING", "WATER_LEAK"]
wire(WATER, "report-leak", "warn-slip", "repair-leak", "restrict-wet-area", "inspect-adjoining", "confirm-repair", "follow-water-sop")
wire(["BLOCKED_DRAIN"], "report-drain", "warn-slip", "clear-drain", "inspect-adjoining", "confirm-repair")
wire(["DRINKING_WATER_CONCERN"], "avoid-water-source", "inspect-water-source", "review-water-checks")

MED = ["FALL_INJURY", "COLLISION_INJURY", "BLEEDING", "UNCONSCIOUS_PERSON", "SUSPECTED_BONE_INJURY", "BURN_INJURY", "HEAT_ILLNESS", "GENERAL_INJURY"]
wire(MED, "medical-contact", "stay-for-responders", "leave-assessment", "inspect-incident-location", "review-recurring-safety")
wire(["SPORTS_INJURY"], "stop-play", "medical-contact", "leave-assessment", "inspect-playing-area", "review-sports-safety", "follow-sports-sop")

wire(["CHEMICAL_SPILL", "BROKEN_CONTAINER"], "avoid-chemical", "report-chemical", "isolate-chemical-area", "specialist-chemical",
     "follow-chemical-sop", "chemical-clearance", "review-chemical-storage")
wire(["CHEMICAL_FUMES"], "leave-area", "report-chemical", "isolate-chemical-area", "specialist-chemical", "follow-chemical-sop",
     "chemical-clearance", "review-chemical-storage")
wire(["GAS_LEAK"], "report-gas", "no-ignition", "leave-area", "inspect-gas-supply", "follow-lpg-isolation-sop", "chemical-clearance", "review-gas-checks")
wire(["BATTERY_SWELLING"], "avoid-battery", "report-battery", "isolate-battery-hazard", "chemical-clearance", "review-chemical-storage")
wire(["ROBOTICS_MALFUNCTION"], "avoid-robot", "report-robot", "inspect-robot", "review-recurring-safety")

wire(["BROKEN_GLASS"], "avoid-glass", "report-damage", "remove-broken-glass", "inspect-similar-items", "review-recurring-safety")
wire(["DAMAGED_FURNITURE"], "stop-using-furniture", "report-damage", "remove-from-use", "inspect-similar-items", "update-capacity", "schedule-furniture-checks")
wire(["CEILING_DAMAGE"], "avoid-overhead", "report-damage", "close-area", "inspect-structure", "review-recurring-safety")
wire(["DOOR_LOCK_FAILURE"], "report-door", "repair-door", "inspect-similar-items", "review-recurring-safety")
wire(["FALLING_OBJECT"], "avoid-overhead", "report-damage", "inspect-structure", "inspect-similar-items", "review-recurring-safety")

wire(["SERVER_OVERHEATING", "COOLING_FAILURE"], "report-infra", "assess-service-impact", "invoke-continuity", "inspect-cooling",
     "verify-cooling", "review-temp-monitoring", "follow-it-sop")
wire(["NETWORK_OUTAGE"], "report-infra", "assess-service-impact", "invoke-continuity", "preserve-logs-ops", "verify-restoration",
     "review-redundancy", "follow-it-sop")
wire(["STORAGE_FAILURE"], "report-infra", "assess-service-impact", "invoke-continuity", "preserve-logs-ops", "avoid-storage-writes",
     "verify-restoration", "verify-data-integrity", "review-redundancy", "follow-it-sop")
wire(["POWER_OUTAGE"], "report-electrical", "inspect-power-supply", "assess-service-impact", "verify-restoration", "review-redundancy")

wire(["LPG_SMELL"], "report-gas", "no-ignition", "leave-area", "inspect-gas-supply", "suspend-service", "follow-lpg-isolation-sop",
     "service-clearance", "review-gas-checks")
wire(["HOT_OIL_SPILL"], "avoid-hot-spill", "report-food-area", "restrict-food-area", "review-food-checks")
wire(["REFRIGERATION_FAILURE"], "avoid-food", "report-food-area", "check-affected-food", "inspect-refrigeration", "review-food-checks", "follow-mess-sop")
wire(["FOOD_CONTAMINATION"], "avoid-food", "report-food-area", "check-affected-food", "review-food-checks", "follow-mess-sop")

wire(["ROAD_ACCIDENT"], "move-from-traffic", "secure-accident-location", "review-road-safety")
wire(["FALLEN_BRANCH"], "avoid-branch", "report-damage", "remove-branch", "inspect-nearby-trees")
wire(["POOR_LIGHTING"], "report-lighting", "repair-lighting", "review-location-security")
wire(["THEFT"], "report-theft", "preserve-evidence-security", "review-location-security")
wire(["ALTERCATION"], "report-altercation", "attend-location", "review-location-security")
wire(["TRAPPED_PERSON"], "report-trapped", "help-trapped", "inspect-involved-equipment")
wire(["BLOCKED_EXIT"], "report-blocked-exit", "remove-exit-obstruction", "inspect-similar-routes", "compliance-follow-up", "follow-compliance-sop")

# medical help whenever someone is reported hurt, for every non-medical type
NON_MED = [t for t in TYPE_CODES if t not in MED and t != "SPORTS_INJURY"]
trig(NON_MED, "medical-contact", ALL, injury=True)

# dependencies (target = prerequisite action)
for a, b in [("reopen-clearance", "fire-inspection"), ("stop-water-source", "isolate-power"), ("verify-cooling", "inspect-cooling"),
             ("service-clearance", "suspend-service"), ("plumbing-electrical-clearance", "isolate-power"),
             ]:
    edge("action:" + a, "DEPENDS_ON", "action:" + b, {}, 50, DEMO)
DEPENDENCIES = [(e["sourceId"][7:], e["targetId"][7:]) for e in edges if e["relation"] == "DEPENDS_ON"]

# hazards and consequences
TYPE_HAZ = {
    "CLASSROOM_FIRE": ["SMOKE", "FIRE"], "HOSTEL_FIRE": ["SMOKE", "FIRE"], "COMPUTER_FIRE": ["SMOKE", "ELECTRICAL_DAMAGE", "DATA_LOSS_RISK"],
    "SERVER_RACK_FIRE": ["SMOKE", "ELECTRICAL_DAMAGE", "DATA_LOSS_RISK", "SERVICE_DISRUPTION"], "KITCHEN_FIRE": ["SMOKE", "FIRE"],
    "COOKING_FIRE": ["SMOKE", "FIRE"], "ELECTRICAL_PANEL_FIRE": ["SMOKE", "ELECTRICAL_DAMAGE", "FIRE"], "FIRE_SMOKE": ["SMOKE", "FIRE"],
    "SPARKING_SOCKET": ["ELECTRICAL_DAMAGE", "ELECTRIC_SHOCK_RISK"], "EXPOSED_WIRE": ["ELECTRIC_SHOCK_RISK"],
    "OVERLOADED_EXTENSION": ["OVERHEATING", "ELECTRICAL_DAMAGE"], "WATER_NEAR_POWER": ["WATER_NEAR_POWER", "ELECTRIC_SHOCK_RISK"],
    "UPS_OVERHEATING": ["OVERHEATING", "ELECTRICAL_DAMAGE"], "ELECTRICAL_FAULT": ["ELECTRICAL_DAMAGE", "ELECTRIC_SHOCK_RISK"],
    "PIPE_LEAK": ["WATER_ACCUMULATION", "SLIP"], "CEILING_LEAK": ["WATER_ACCUMULATION", "SLIP"], "WASHROOM_FLOODING": ["WATER_ACCUMULATION", "SLIP"],
    "BLOCKED_DRAIN": ["WATER_ACCUMULATION", "SLIP"], "DRINKING_WATER_CONCERN": ["CONTAMINATION"], "WATER_LEAK": ["WATER_ACCUMULATION", "SLIP"],
    "CHEMICAL_SPILL": ["CHEMICAL_EXPOSURE"], "CHEMICAL_FUMES": ["FUMES"], "GAS_LEAK": ["GAS_LEAK"],
    "BROKEN_CONTAINER": ["CHEMICAL_EXPOSURE", "SHARP_EDGES"], "BATTERY_SWELLING": ["BATTERY_HAZARD"], "ROBOTICS_MALFUNCTION": ["MOVING_MACHINERY"],
    "BROKEN_GLASS": ["SHARP_EDGES"], "DAMAGED_FURNITURE": ["UNSTABLE_FURNITURE"], "CEILING_DAMAGE": ["FALLING_OBJECT", "STRUCTURAL_DAMAGE"],
    "DOOR_LOCK_FAILURE": ["ACCESS_PROBLEM"], "FALLING_OBJECT": ["FALLING_OBJECT"],
    "SERVER_OVERHEATING": ["OVERHEATING", "SERVICE_DISRUPTION"], "COOLING_FAILURE": ["OVERHEATING", "SERVICE_DISRUPTION"],
    "STORAGE_FAILURE": ["SERVICE_DISRUPTION", "DATA_LOSS_RISK"], "NETWORK_OUTAGE": ["SERVICE_DISRUPTION"], "POWER_OUTAGE": ["SERVICE_DISRUPTION"],
    "LPG_SMELL": ["GAS_LEAK"], "HOT_OIL_SPILL": ["HOT_OIL", "SLIP"], "REFRIGERATION_FAILURE": ["CONTAMINATION"], "FOOD_CONTAMINATION": ["CONTAMINATION"],
    "ROAD_ACCIDENT": ["TRAFFIC", "INJURED_PERSON"], "FALLEN_BRANCH": ["FALLING_OBJECT"], "POOR_LIGHTING": ["LOW_VISIBILITY"], "THEFT": [],
    "ALTERCATION": ["PERSONAL_SAFETY"], "TRAPPED_PERSON": ["PERSON_TRAPPED"], "BLOCKED_EXIT": ["BLOCKED_EXIT"],
}
for t in MED + ["SPORTS_INJURY"]:
    TYPE_HAZ[t] = ["INJURED_PERSON"]
assert set(TYPE_HAZ) == set(TYPE_CODES), set(TYPE_CODES) ^ set(TYPE_HAZ)
for t in TYPE_CODES:
    for h in TYPE_HAZ[t]:
        edge("incident-type:" + slug(t), "PRODUCES_HAZARD", "hazard:" + slug(h), {"statuses": ALL}, 50, DEMO)

HAZ_CONS = {
    "SMOKE": ["smoke-spread", "smoke-exposure"], "FIRE": ["fire-spread", "structural-damage"], "ELECTRICAL_DAMAGE": ["equipment-damage", "room-closure"],
    "ELECTRIC_SHOCK_RISK": ["shock-exposure"], "WATER_NEAR_POWER": ["water-reaches-power", "shock-exposure"],
    "SLIP": ["slip-harm"], "WATER_ACCUMULATION": ["water-reaches-power", "repeated-failure"], "GAS_LEAK": ["gas-buildup"],
    "CHEMICAL_EXPOSURE": ["chem-exposure", "contamination-spread"], "FUMES": ["chem-exposure"], "BLOCKED_EXIT": ["blocked-route"],
    "ACCESS_PROBLEM": ["blocked-route"], "OVERHEATING": ["thermal-failure"], "FALLING_OBJECT": ["harm-nearby"], "SHARP_EDGES": ["harm-nearby"],
    "STRUCTURAL_DAMAGE": ["structural-damage", "room-closure"], "UNSTABLE_FURNITURE": ["harm-nearby", "reduced-capacity"],
    "BATTERY_HAZARD": ["thermal-failure", "harm-nearby"], "CONTAMINATION": ["food-unsafe"], "HOT_OIL": ["harm-nearby"], "TRAFFIC": ["harm-nearby"],
    "LOW_VISIBILITY": ["poor-visibility"], "PERSONAL_SAFETY": ["harm-nearby"], "PERSON_TRAPPED": ["trapped-risk"],
    "MOVING_MACHINERY": ["harm-nearby"], "INJURED_PERSON": ["medical-attention"], "DATA_LOSS_RISK": ["data-loss"],
    "SERVICE_DISRUPTION": ["service-outage", "workstation-unavailable"],
}
assert set(HAZ_CONS) == {h[0] for h in HAZARDS}
for h, cs in HAZ_CONS.items():
    for c in cs:
        edge("hazard:" + slug(h), "MAY_CAUSE", "consequence:" + c, {}, 50, DEMO)

CONS_ACT = [  # consequence, action, extra conditions
    ("smoke-exposure", "leave-area", {}), ("slip-harm", "warn-slip", {}), ("gas-buildup", "no-ignition", {}),
    ("chem-exposure", "avoid-chemical", {}), ("service-outage", "assess-service-impact", {}),
    ("data-loss", "preserve-logs-recovery", {"statuses": POST}), ("reduced-capacity", "update-capacity", {}),
    ("food-unsafe", "avoid-food", {}), ("blocked-route", "report-blocked-exit", {}),
    ("water-reaches-power", "exclusion-zone-water-power", {"hazardsAny": ["WATER_NEAR_POWER"]}),
    ("water-reaches-power", "isolate-power", {"hazardsAny": ["WATER_NEAR_POWER"]}),
]
for c, s, extra in CONS_ACT:
    cond = {"statuses": ALL}
    cond.update(extra)
    edge("consequence:" + c, "REQUIRES_ACTION", "action:" + s, cond, ACTIONS[s]["prio"], DEMO)

ASSET_ACT = [  # asset codes, action, incident types
    (["DESKTOP", "LAPTOP", "SERVER"], "isolate-asset", ["COMPUTER_FIRE", "SERVER_RACK_FIRE"]),
    (["DESKTOP", "LAPTOP", "SERVER"], "preserve-logs-recovery", ["COMPUTER_FIRE", "SERVER_RACK_FIRE"]),
    (["DESKTOP", "LAPTOP"], "replacement-workstation", ["COMPUTER_FIRE"]),
    (["POWER_STRIP", "SOCKET", "WIRING", "ELECTRICAL_PANEL"], "isolate-power", ELEC + ["WATER_NEAR_POWER", "ELECTRICAL_PANEL_FIRE"]),
    (["UPS"], "inspect-ups", ["UPS_OVERHEATING"]),
    (["COOKING_EQUIPMENT"], "suspend-service", ["KITCHEN_FIRE", "COOKING_FIRE", "LPG_SMELL"]),
    (["LPG_CYLINDER"], "inspect-gas-supply", ["LPG_SMELL", "GAS_LEAK"]),
    (["REFRIGERATION"], "inspect-refrigeration", ["REFRIGERATION_FAILURE"]),
    (["FURNITURE"], "remove-from-use", ["DAMAGED_FURNITURE"]),
    (["DOOR", "FIRE_EXIT"], "remove-exit-obstruction", ["BLOCKED_EXIT"]),
    (["DOOR"], "repair-door", ["DOOR_LOCK_FAILURE"]),
    (["WINDOW_GLASS"], "remove-broken-glass", ["BROKEN_GLASS"]),
    (["CEILING"], "inspect-structure", ["CEILING_DAMAGE", "FALLING_OBJECT"]),
    (["STORAGE_SYSTEM"], "preserve-logs-ops", ["STORAGE_FAILURE", "NETWORK_OUTAGE"]),
    (["STORAGE_SYSTEM"], "verify-data-integrity", ["STORAGE_FAILURE"]),
    (["NETWORK_EQUIPMENT"], "assess-service-impact", ["NETWORK_OUTAGE"]),
    (["COOLING_UNIT"], "inspect-cooling", ["COOLING_FAILURE", "SERVER_OVERHEATING"]),
    (["PIPE"], "repair-leak", ["PIPE_LEAK", "WATER_LEAK", "CEILING_LEAK"]),
    (["DRAIN"], "clear-drain", ["BLOCKED_DRAIN"]),
    (["BATTERY"], "isolate-battery-hazard", ["BATTERY_SWELLING"]),
    (["CHEMICAL_CONTAINER"], "isolate-chemical-area", ["CHEMICAL_SPILL", "BROKEN_CONTAINER"]),
    (["LIGHTING"], "repair-lighting", ["POOR_LIGHTING"]),
    (["SPORTS_EQUIPMENT"], "inspect-playing-area", ["SPORTS_INJURY"]),
]
for assets, s, types in ASSET_ACT:
    a = ACTIONS[s]
    for ac in assets:
        edge("asset-type:" + slug(ac), "REQUIRES_ACTION", "action:" + s,
             {"incidentTypes": types, "statuses": default_status(a["phase"])}, a["prio"], DEMO)

ROOM_TYPES = ["FIRE_SMOKE", "CLASSROOM_FIRE", "ELECTRICAL_PANEL_FIRE", "CEILING_LEAK", "CEILING_DAMAGE", "PIPE_LEAK", "WATER_LEAK",
              "WATER_NEAR_POWER", "FALLING_OBJECT", "BROKEN_GLASS", "POWER_OUTAGE"]
IT_TYPES = ["SERVER_OVERHEATING", "COOLING_FAILURE", "STORAGE_FAILURE", "NETWORK_OUTAGE", "POWER_OUTAGE", "SERVER_RACK_FIRE"]
CONT = [  # location codes, arrangement, incident types, priority
    (["CLASSROOM", "SEMINAR_ROOM", "AUDITORIUM"], "alternate-room", ROOM_TYPES, 80),
    (["CLASSROOM", "SEMINAR_ROOM", "AUDITORIUM"], "verified-alternate-room", ROOM_TYPES, 70),
    (["CLASSROOM", "SEMINAR_ROOM", "AUDITORIUM"], "alternate-seating", ["DAMAGED_FURNITURE"], 80),
    (["CLASSROOM", "SEMINAR_ROOM", "AUDITORIUM"], "verified-alternate-room", ["DAMAGED_FURNITURE"], 70),
    (["COMPUTER_LAB", "RESEARCH_LAB", "ELECTRONICS_LAB", "ROBOTICS_SPACE"], "alternate-lab", ROOM_TYPES + ["COMPUTER_FIRE", "CHEMICAL_SPILL", "BROKEN_CONTAINER", "CHEMICAL_FUMES"], 80),
    (["COMPUTER_LAB", "RESEARCH_LAB", "ELECTRONICS_LAB", "ROBOTICS_SPACE"], "verified-alternate-room", ROOM_TYPES + ["COMPUTER_FIRE"], 70),
    (["COMPUTER_LAB"], "temporary-workstations", ["COMPUTER_FIRE", "POWER_OUTAGE", "FIRE_SMOKE"], 75),
    (["HOSTEL_ROOM", "HOSTEL_CORRIDOR", "HOSTEL_COMMON", "HOSTEL_WASHROOM"], "temporary-accommodation",
     ["HOSTEL_FIRE", "FIRE_SMOKE", "PIPE_LEAK", "WATER_LEAK", "CEILING_LEAK", "WASHROOM_FLOODING"], 80),
    (["KITCHEN", "DINING_AREA", "CANTEEN", "FOOD_STORE", "COLD_STORAGE"], "alternate-meal-service",
     ["LPG_SMELL", "COOKING_FIRE", "KITCHEN_FIRE", "REFRIGERATION_FAILURE", "FOOD_CONTAMINATION", "HOT_OIL_SPILL", "FIRE_SMOKE", "POWER_OUTAGE"], 80),
    (["SERVER_ROOM", "NETWORK_ROOM", "COMPUTER_LAB"], "interim-service-workaround", IT_TYPES, 75),
    (["WASHROOM", "HOSTEL_WASHROOM"], "alternate-washroom", ["WATER_LEAK", "PIPE_LEAK", "WASHROOM_FLOODING", "BLOCKED_DRAIN", "CEILING_LEAK"], 75),
]
for locs, s, types, pr in CONT:
    for lc in locs:
        edge("location-type:" + slug(lc), "HAS_CONTINUITY_OPTION", "arrangement:" + s, {"incidentTypes": types, "statuses": ALL}, pr, DEMO)

# ---------------------------------------------------------------------------------------------------------------- normalization
def R_(code, any_=None, all_=None, apps=None):
    r = {"code": code}
    if apps:
        r["appTypes"] = apps
    if any_:
        r["anyKeywords"] = any_
    if all_:
        r["allKeywords"] = all_
    return r


def X(code, a_list, b_list):
    return [R_(code, any_=b_list, all_=[a]) for a in a_list]


FS = ["fire", "smoke", "flames"]
LEAKW = ["leak", "leaks", "leaking", "leaked"]
TYPE_RULES = []
TR = TYPE_RULES.extend
TR(X("SERVER_RACK_FIRE", FS, ["server", "servers", "rack", "racks"]))
TR(X("ELECTRICAL_PANEL_FIRE", FS, ["panel", "distribution board", "switchboard"]))
TR(X("COMPUTER_FIRE", FS, ["computer", "computers", "desktop", "laptop", "workstation", "monitor", "pc"]))
TR(X("COOKING_FIRE", FS, ["stove", "oil", "cooking", "fryer", "burner"]))
TR(X("KITCHEN_FIRE", FS, ["kitchen", "canteen", "cafeteria"]))
TR(X("CLASSROOM_FIRE", FS, ["classroom", "lecture hall", "lecture room", "seminar room"]))
TR(X("HOSTEL_FIRE", FS, ["hostel", "dorm"]))
TR(X("WATER_NEAR_POWER", ["water", "wet"], ["power", "socket", "plug", "extension", "switchboard"]))
TR(X("UPS_OVERHEATING", ["ups"], ["overheating", "overheated", "hot", "heating"]))
TR(X("SPARKING_SOCKET", ["socket", "switchboard", "plug", "switch"], ["spark", "sparks", "sparking", "sparked"]))
TR(X("EXPOSED_WIRE", ["wire", "wires", "wiring", "cable", "cables"], ["exposed", "bare", "loose", "hanging"]))
TR(X("OVERLOADED_EXTENSION", ["extension", "multiplug", "power strip"], ["overloaded", "overload", "melting", "melted", "hot"]))
TYPE_RULES.append(R_("POWER_OUTAGE", any_=["power outage", "power cut", "power failure", "blackout", "no power", "power is out"]))
TR(X("COOLING_FAILURE", ["cooling", "air conditioner", "air conditioning", "chiller", "ac"], ["failed", "failure", "stopped", "down", "off"]))
TR(X("SERVER_OVERHEATING", ["server", "servers", "rack"], ["overheating", "overheated", "hot", "temperature"]))
TR(X("NETWORK_OUTAGE", ["network", "internet", "wifi", "lan"], ["down", "outage", "unavailable", "failed", "failure", "unreachable"]))
TR(X("STORAGE_FAILURE", ["storage", "nas", "disk", "file server"], ["failed", "failure", "down", "unavailable", "outage", "unreachable", "corrupted"]))
TYPE_RULES.append(R_("LPG_SMELL", any_=["lpg"]))
TR(X("LPG_SMELL", ["gas", "cylinder"], ["smell", "smells", "smelling", "odour", "odor"]))
TR(X("HOT_OIL_SPILL", ["oil"], ["spill", "spilled", "spilt", "spillage"]))
TR(X("REFRIGERATION_FAILURE", ["fridge", "refrigerator", "freezer", "cold storage", "cold room"], ["failed", "failure", "stopped", "warm", "not cooling"]))
TR(X("FOOD_CONTAMINATION", ["food", "meal"], ["contaminated", "spoiled", "rotten", "stale", "insect", "insects", "worm"]))
TR(X("GAS_LEAK", ["gas"], LEAKW))
TYPE_RULES.append(R_("CHEMICAL_FUMES", any_=["fumes", "fume", "chemical smell", "pungent smell"]))
TR(X("CHEMICAL_SPILL", ["chemical", "acid", "reagent", "solvent", "alkali"], ["spill", "spilled", "spilt", "spillage"]))
TR(X("BROKEN_CONTAINER", ["bottle", "container", "flask", "beaker"], ["broken", "shattered", "cracked", "dropped"]))
TR(X("BATTERY_SWELLING", ["battery", "batteries", "lithium"], ["swollen", "swelling", "bulging", "bulged"]))
TR(X("ROBOTICS_MALFUNCTION", ["robot", "robots", "robotic"], ["malfunction", "malfunctioned", "erratic", "out of control", "failure"]))
TR(X("DRINKING_WATER_CONCERN", ["drinking", "water purifier", "water cooler"], ["smell", "smells", "dirty", "contaminated", "taste", "yellow", "muddy", "unsafe"]))
TR(X("WASHROOM_FLOODING", ["washroom", "toilet", "bathroom", "restroom"], ["flooding", "flooded", "overflowing", "overflow"]))
TR(X("CEILING_LEAK", ["ceiling"], LEAKW + ["dripping", "seepage"]))
TR(X("PIPE_LEAK", ["pipe", "pipes", "pipeline"], LEAKW + ["burst"]))
TR(X("BLOCKED_DRAIN", ["drain", "drains", "drainage"], ["blocked", "clogged", "choked", "overflowing"]))
TR(X("CEILING_DAMAGE", ["ceiling"], ["damaged", "collapsed", "cracked", "crack", "peeling", "plaster", "falling"]))
TYPE_RULES.append(R_("BROKEN_GLASS", any_=["broken glass", "shattered glass"]))
TR(X("BROKEN_GLASS", ["glass", "window", "windows", "mirror"], ["broken", "shattered", "cracked"]))
TR(X("DAMAGED_FURNITURE", ["chair", "chairs", "desk", "desks", "bench", "benches", "table", "tables", "furniture", "cupboard", "shelf"],
     ["broken", "damaged", "collapsed", "cracked"]))
TR(X("BLOCKED_EXIT", ["exit"], ["blocked", "obstructed", "jammed", "locked"]))
TYPE_RULES.append(R_("BLOCKED_EXIT", any_=["blocked exit", "blocked fire exit"]))
TYPE_RULES.append(R_("TRAPPED_PERSON", any_=["trapped"]))
TR(X("TRAPPED_PERSON", ["stuck"], ["lift", "elevator"]))
TYPE_RULES.append(R_("ROAD_ACCIDENT", any_=["road accident", "road crash"]))
TR(X("ROAD_ACCIDENT", ["bike", "car", "scooter", "vehicle", "motorcycle", "cycle"], ["accident", "crash", "crashed", "collided"]))
TR(X("FALLEN_BRANCH", ["branch", "tree"], ["fallen", "fell", "broke", "broken", "fall"]))
TYPE_RULES.append(R_("FALLING_OBJECT", any_=["falling object", "falling objects"]))
TR(X("FALLING_OBJECT", ["fan", "tile", "tiles", "object"], ["fell", "fallen", "falling"]))
TR(X("DOOR_LOCK_FAILURE", ["door", "lock", "latch", "handle"], ["stuck", "jammed", "broken", "failed"]))
TYPE_RULES.append(R_("POOR_LIGHTING", any_=["poor lighting", "no light"]))
TR(X("POOR_LIGHTING", ["light", "lights", "lighting", "streetlight", "lamp"], ["dark", "dim", "flickering", "failed", "broken"]))
TYPE_RULES.append(R_("THEFT", any_=["theft", "stolen", "stole", "thief", "robbery", "pickpocket"]))
TYPE_RULES.append(R_("ALTERCATION", any_=["altercation", "fight", "fighting", "quarrel", "scuffle", "assault"]))
TR(X("SPORTS_INJURY", ["football", "cricket", "basketball", "volleyball", "badminton", "tennis", "sports"],
     ["injured", "injury", "hurt", "fell", "fall", "collided", "collision", "twisted", "pain"]))
TYPE_RULES.append(R_("BLEEDING", any_=["bleeding", "blood", "bleed"]))
TYPE_RULES.append(R_("UNCONSCIOUS_PERSON", any_=["unconscious", "fainted", "passed out", "not responding", "collapsed"]))
TYPE_RULES.append(R_("BURN_INJURY", any_=["burn", "burns", "scald", "scalded", "burn injury"]))
TYPE_RULES.append(R_("HEAT_ILLNESS", any_=["heatstroke", "heat stroke", "heat exhaustion", "heat illness", "dehydrated"]))
TYPE_RULES.append(R_("SUSPECTED_BONE_INJURY", any_=["fracture", "fractured", "broken bone", "broken leg", "broken arm", "broken wrist", "broken ankle"]))
TYPE_RULES.append(R_("COLLISION_INJURY", any_=["collision", "collided"]))
TYPE_RULES.append(R_("FALL_INJURY", apps=["Slip / Fall"], any_=["fell", "fall", "slipped", "tripped"]))
# generic, app-type driven fallbacks last
TYPE_RULES.append(R_("FIRE_SMOKE", apps=["Fire", "Fire / Smoke"], any_=["fire", "smoke", "flames", "burning"]))
TYPE_RULES.append(R_("WATER_LEAK", apps=["Water Leak"], any_=LEAKW + ["flooding", "flooded"]))
TYPE_RULES.append(R_("ELECTRICAL_FAULT", apps=["Electrical Hazard"], any_=["short circuit", "sparks", "sparking", "shock"]))
TYPE_RULES.append(R_("CHEMICAL_SPILL", apps=["Spill"]))
TYPE_RULES.append(R_("GAS_LEAK", apps=["Gas Leak"]))
TYPE_RULES.append(R_("GENERAL_INJURY", apps=["Injury", "Accident"], any_=["injured", "injury", "hurt"]))

C = lambda code, kws: {"code": code, "anyKeywords": kws}
LOCATION_RULES = [
    C("SERVER_ROOM", ["server room", "data center", "data centre"]), C("NETWORK_ROOM", ["network room", "network closet", "telecom room"]),
    C("ELECTRICAL_ROOM", ["electrical room", "panel room", "transformer room", "substation"]),
    C("COMPUTER_LAB", ["computer lab", "computer labs", "computer laboratory", "pc lab", "computing lab"]),
    C("ELECTRONICS_LAB", ["electronics lab", "electronics laboratory", "vlsi lab", "embedded lab"]),
    C("ROBOTICS_SPACE", ["robotics lab", "robotics space", "robotics room", "drone lab"]),
    C("RESEARCH_LAB", ["lab", "labs", "laboratory", "research lab"]),
    C("HOSTEL_WASHROOM", ["hostel washroom", "hostel bathroom", "hostel toilet"]),
    C("HOSTEL_CORRIDOR", ["hostel corridor", "hostel stairs", "hostel staircase", "hostel lift"]),
    C("HOSTEL_ROOM", ["hostel room", "dorm room", "dorm"]),
    C("HOSTEL_COMMON", ["hostel", "hostels", "hostel block", "hostel common room"]),
    C("SEMINAR_ROOM", ["seminar room", "seminar hall", "meeting room", "conference room"]),
    C("AUDITORIUM", ["auditorium", "convocation hall"]),
    C("CLASSROOM", ["classroom", "classrooms", "class room", "lecture hall", "lecture room", "tutorial room"]),
    C("WASHROOM", ["washroom", "washrooms", "toilet", "restroom", "bathroom", "wash room"]),
    C("PANTRY", ["pantry", "tea point"]), C("LIFT", ["lift", "elevator"]), C("STAIRS", ["stairs", "staircase", "stairwell"]),
    C("CORRIDOR", ["corridor", "hallway", "passage"]),
    C("KITCHEN", ["kitchen", "cooking area"]), C("CANTEEN", ["canteen", "cafeteria", "food court"]),
    C("FOOD_STORE", ["food store", "store room", "storeroom", "ration store"]), C("COLD_STORAGE", ["cold storage", "cold room"]),
    C("DINING_AREA", ["dining", "dining hall", "mess hall", "mess"]),
    C("HEALTH_FACILITY", ["health centre", "health center", "medical centre", "medical center", "health facility", "clinic", "infirmary"]),
    C("SUPPORT_OFFICE", ["maintenance office", "it support", "facilities office", "security office", "guard room"]),
    C("LIBRARY", ["library"]), C("SHOP", ["shop", "stationery", "campus store"]), C("ATM", ["atm"]), C("LAUNDRY", ["laundry"]),
    C("RECEPTION", ["reception", "front desk"]),
    C("FOOTBALL_GROUND", ["football ground", "football field", "football", "soccer"]),
    C("SPORTS_COURT", ["court", "basketball court", "tennis court", "badminton court", "volleyball court", "sports complex"]),
    C("PARKING", ["parking", "parking lot"]), C("FOOTPATH", ["footpath", "pathway", "walkway", "pavement"]),
    C("ROAD", ["road", "main road"]), C("OPEN_GROUND", ["ground", "grounds", "lawn", "garden", "open ground"]),
]
ASSET_RULES = [
    C("DESKTOP", ["desktop", "desktops", "computer", "computers", "pc", "pcs", "workstation", "workstations", "monitor", "cpu"]),
    C("LAPTOP", ["laptop", "laptops"]), C("SERVER", ["server", "servers", "rack", "racks"]),
    C("NETWORK_EQUIPMENT", ["router", "switch", "network switch", "network equipment", "access point"]),
    C("STORAGE_SYSTEM", ["storage system", "storage", "nas"]), C("COOLING_UNIT", ["cooling system", "cooling unit", "air conditioner", "chiller", "ac"]),
    C("UPS", ["ups", "uninterruptible power supply"]), C("POWER_STRIP", ["power strip", "extension board", "extension cord", "multiplug", "surge protector"]),
    C("SOCKET", ["socket", "sockets", "switchboard", "plug"]), C("WIRING", ["wire", "wires", "wiring", "cable", "cables"]),
    C("ELECTRICAL_PANEL", ["electrical panel", "distribution board", "panel"]),
    C("COOKING_EQUIPMENT", ["stove", "gas stove", "burner", "fryer", "oven", "cooking equipment"]),
    C("LPG_CYLINDER", ["cylinder", "cylinders", "lpg cylinder", "gas cylinder"]),
    C("REFRIGERATION", ["fridge", "refrigerator", "freezer"]),
    C("FURNITURE", ["chair", "chairs", "desk", "desks", "bench", "benches", "table", "tables", "furniture", "cupboard", "shelf", "shelves"]),
    C("DOOR", ["door", "doors", "lock"]), C("FIRE_EXIT", ["fire exit", "emergency exit"]),
    C("WINDOW_GLASS", ["glass", "window", "windows"]), C("CEILING", ["ceiling"]),
    C("PIPE", ["pipe", "pipes", "pipeline", "tap", "taps"]), C("DRAIN", ["drain", "drains", "drainage"]),
    C("BATTERY", ["battery", "batteries"]), C("LIGHTING", ["light", "lights", "lamp", "lamps", "streetlight", "tubelight"]),
    C("CHEMICAL_CONTAINER", ["bottle", "bottles", "container", "containers", "flask", "beaker"]),
    C("SPORTS_EQUIPMENT", ["goalpost", "goal post", "sports equipment"]),
]
HAZARD_RULES = [
    C("SMOKE", ["smoke", "smoky", "smoking"]), C("FIRE", ["flames", "flame", "burning", "blaze", "on fire"]),
    C("ELECTRICAL_DAMAGE", ["short circuit", "sparks", "spark", "sparking", "melted", "tripped"]),
    C("ELECTRIC_SHOCK_RISK", ["exposed wire", "exposed wires", "bare wire", "electric shock", "live wire"]),
    C("WATER_NEAR_POWER", ["water near the power strip", "water near power strip", "water near the socket", "water near socket",
                           "water near the plug", "water near the extension", "wet socket", "wet power strip"]),
    C("SLIP", ["slippery", "wet floor", "slip"]),
    C("WATER_ACCUMULATION", ["flooding", "flooded", "water on the floor", "standing water"]),
    C("GAS_LEAK", ["gas leak", "gas smell", "smell of gas", "lpg", "leaking gas", "gas leaking"]),
    C("CHEMICAL_EXPOSURE", ["chemical", "chemicals", "acid", "toxic", "hazardous", "reagent", "solvent"]),
    C("FUMES", ["fumes", "fume", "pungent smell", "chemical smell"]),
    C("BLOCKED_EXIT", ["blocked exit", "exit blocked", "exit is blocked", "blocked fire exit", "fire exit is blocked", "fire exit blocked"]),
    C("OVERHEATING", ["overheating", "overheated", "getting hot", "too hot", "very hot"]),
    C("FALLING_OBJECT", ["falling object", "falling objects", "about to fall", "hanging loose"]),
    C("SHARP_EDGES", ["broken glass", "shattered glass", "glass pieces", "sharp edges"]),
    C("STRUCTURAL_DAMAGE", ["collapsed", "collapse", "structural"]), C("UNSTABLE_FURNITURE", ["unstable", "wobbly"]),
    C("BATTERY_HAZARD", ["swollen battery", "battery swelling", "bulging battery", "battery is swollen"]),
    C("CONTAMINATION", ["contaminated", "contamination", "spoiled", "rotten", "dirty water"]), C("HOT_OIL", ["hot oil"]),
    C("TRAFFIC", ["traffic", "moving vehicle"]), C("LOW_VISIBILITY", ["dark", "poor lighting", "no light", "low visibility"]),
    C("PERSONAL_SAFETY", ["threat", "threatened", "assault", "weapon"]),
    C("MOVING_MACHINERY", ["out of control", "moving parts"]),
    C("DATA_LOSS_RISK", ["data loss", "files lost", "lost data"]),
]
EFFECT_RULES = [
    C("WORKSTATION_UNAVAILABLE", ["desktop is unusable", "computer is unusable", "workstation is unusable", "pc is unusable"]),
    C("ROOM_UNUSABLE", ["room is unusable", "cannot use the room", "room is closed", "lab is closed", "class cancelled"]),
    C("SERVICE_OUTAGE", ["outage", "services are unavailable", "service is unavailable", "services unavailable", "services are down", "services down"]),
]
STATUS_RULES = [
    {"status": "CONTAINED", "anyKeywords": ["contained", "isolated", "extinguished", "under control", "stopped", "controlled"]},
    {"status": "RESOLVED", "anyKeywords": ["fixed", "repaired", "resolved", "cleared", "back to normal", "restored"]},
    {"status": "ACTIVE", "anyKeywords": ["still", "ongoing", "spreading", "leaking", "leaks", "burning", "continuing", "keeps"]},
]
BUILDING_RULES = [
    C("building:krb", ["krb", "kohli", "kohli research block"]), C("building:nilgiri", ["nilgiri"]), C("building:himalaya", ["himalaya"]),
    C("building:library", ["library"]),
    C("building:health-facility", ["health centre", "health center", "medical centre", "clinic", "dispensary"]),
    C("building:mess-area", ["mess", "kitchen", "canteen", "cafeteria", "dining hall"]),
    C("building:hostels-area", ["hostel", "hostels", "hostel block"]),
    C("building:outdoor-area", ["football ground", "ground", "grounds", "sports complex", "court", "road", "parking", "footpath", "garden", "lawn"]),
]
NORMALIZATION = {"incidentTypes": TYPE_RULES, "locationTypes": LOCATION_RULES, "assets": ASSET_RULES, "hazards": HAZARD_RULES,
                 "operationalEffects": EFFECT_RULES, "statuses": STATUS_RULES, "buildings": BUILDING_RULES,
                 "residentialLocationTypes": ["HOSTEL_ROOM", "HOSTEL_CORRIDOR", "HOSTEL_COMMON", "HOSTEL_WASHROOM"]}

PACK = {"schemaVersion": 1, "packVersion": 1, "name": "iiit-h-seed",
        "description": "Demo seed knowledge graph for IIIT-H post-incident analysis. Generic guidance only: no contacts, numbers, capacities, room numbers or real SOPs. "
                       "Every node needs IIIT-H admin verification before production use.",
        "createdAt": "2026-10-11T00:00:00+05:30", "nodes": nodes, "edges": edges, "sources": SOURCES, "normalization": NORMALIZATION}

# ---------------------------------------------------------------------------------------------------------------- python mirror of the Kotlin semantics
def tokens(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def has_phrase(toks, kw):
    k = tokens(kw)
    n = len(k)
    return any(toks[i:i + n] == k for i in range(len(toks) - n + 1))


def type_rule_hit(r, apptype, toks):
    if apptype and apptype.lower() in [a.lower() for a in r.get("appTypes", [])]:
        return True
    an, al = r.get("anyKeywords", []), r.get("allKeywords", [])
    if not an and not al:
        return False
    return (not an or any(has_phrase(toks, k) for k in an)) and all(has_phrase(toks, k) for k in al)


def normalize(report):
    """What the Kotlin adapter must produce from a saved report (see DialogueCore's rule description)."""
    app, desc, place = report["appType"], report["description"] or "", report["location"] or ""
    ia = report.get("immediateActions") or ""
    all_t = tokens(" ".join([app, desc, place]))
    typ = next((r["code"] for r in TYPE_RULES if type_rule_hit(r, app, all_t)), None)
    place_t, desc_t = tokens(place), tokens(desc)
    loc = next((r["code"] for r in LOCATION_RULES if any(has_phrase(place_t, k) for k in r["anyKeywords"])), None)
    if loc is None:
        loc = next((r["code"] for r in LOCATION_RULES if any(has_phrase(desc_t, k) for k in r["anyKeywords"])), None)
    bld = next((r["code"] for r in BUILDING_RULES if any(has_phrase(place_t, k) for k in r["anyKeywords"])), None)
    hit = lambda rules: [r["code"] for r in rules if any(has_phrase(all_t, k) for k in r["anyKeywords"])]
    if report.get("hazardStillActive") is True:
        status = "ACTIVE"
    else:
        st = tokens(desc + " " + ia)
        status = next((r["status"] for r in STATUS_RULES if any(has_phrase(st, k) for k in r["anyKeywords"])), "UNKNOWN")
    return {"type": typ, "locationType": loc, "locationId": bld, "status": status, "assets": hit(ASSET_RULES),
            "hazards": hit(HAZARD_RULES), "operationalEffects": hit(EFFECT_RULES)}


def cond_ok(c, inc):
    if c.get("incidentTypes") and inc["type"] not in c["incidentTypes"]:
        return False
    if c.get("locationTypes") and inc["locationType"] not in c["locationTypes"]:
        return False
    if c.get("assetTypes") and not set(c["assetTypes"]) & set(inc["assets"]):
        return False
    if c.get("statuses") and inc["status"] not in c["statuses"]:
        return False
    if c.get("hazardsAny") and not set(c["hazardsAny"]) & set(inc["hazards"]):
        return False
    if c.get("hazardsAll") and not set(c["hazardsAll"]) <= set(inc["hazards"]):
        return False
    if c.get("hazardsNone") and set(c["hazardsNone"]) & set(inc["hazards"]):
        return False
    if c.get("injury") is not None and inc.get("injury") != c["injury"]:
        return False
    if c.get("operationalEffectsAny") and not set(c["operationalEffectsAny"]) & set(inc["operationalEffects"]):
        return False
    return True


OUT = {}
for e in edges:
    OUT.setdefault((e["sourceId"], e["relation"]), []).append(e)


def code_node(ntype_prefix, code):
    return ntype_prefix + ":" + slug(code)


def simulate(inc, admin=False):
    """Mini traversal of the whitelisted paths: returns {phase: [action slugs]} and arrangement slugs (ranking is not modelled)."""
    cands = {}

    def add(s, path):
        cands.setdefault(s, path)

    tnode = code_node("incident-type", inc["type"])
    for e in OUT.get((tnode, "TRIGGERS_ACTION"), []):
        if cond_ok(e["conditions"], inc):
            add(e["targetId"][7:], e["id"])
    for e in OUT.get((tnode, "PRODUCES_HAZARD"), []):
        if not cond_ok(e["conditions"], inc):
            continue
        for e2 in OUT.get((e["targetId"], "MAY_CAUSE"), []):
            for e3 in OUT.get((e2["targetId"], "REQUIRES_ACTION"), []):
                if cond_ok(e3["conditions"], inc):
                    add(e3["targetId"][7:], e3["id"])
    for ac in inc["assets"]:
        for e in OUT.get((code_node("asset-type", ac), "REQUIRES_ACTION"), []):
            if cond_ok(e["conditions"], inc):
                add(e["targetId"][7:], e["id"])
    by_key = {}
    for s in cands:
        a = ACTIONS[s]
        if a["draft"] and not admin:
            continue
        if a["user"] is True and not a["user"]:
            continue
        k = a["key"]
        if k not in by_key or ACTIONS[by_key[k]]["prio"] < a["prio"]:
            by_key[k] = s
    phases = {I: [], O: [], R: [], P: []}
    for s in sorted(by_key.values(), key=lambda x: (-ACTIONS[x]["prio"], x)):
        phases[ACTIONS[s]["phase"]].append(s)
    arr = []
    if inc["locationType"]:
        for e in OUT.get((code_node("location-type", inc["locationType"]), "HAS_CONTINUITY_OPTION"), []):
            if cond_ok(e["conditions"], inc):
                arr.append(e["targetId"][len("arrangement:"):])
    return phases, sorted(set(arr)), cands


# ---------------------------------------------------------------------------------------------------------------- scenarios
SCENARIOS = [
    ("1 Classroom fire", {"appType": "Fire", "description": "A fire broke out in a classroom and has now been contained. The room is closed and smoke remains.",
                          "location": "Nilgiri classroom", "hazardStillActive": False, "injury": False, "severity": "High", "immediateActions": "Everyone left the room"}),
    ("2 Computer fire", {"appType": "Fire", "description": "A desktop computer caught fire in the computer lab. The fire is now isolated and the desktop is unusable, a little smoke remains.",
                         "location": "KRB computer lab", "hazardStillActive": False, "injury": False, "severity": "High", "immediateActions": None}),
    ("3 Server cooling failure", {"appType": "Equipment Failure", "description": "The cooling system in the server room has failed and the servers are getting hot. The problem is still there.",
                                  "location": "KRB server room", "hazardStillActive": True, "injury": False, "severity": "High", "immediateActions": None}),
    ("4 Water near power strip", {"appType": "Electrical Hazard", "description": "There is water near the power strip on the floor and it is still spreading.",
                                  "location": "Himalaya lab", "hazardStillActive": True, "injury": False, "severity": "High", "immediateActions": None}),
    ("5 Repeated washroom leak", {"appType": "Water Leak", "description": "Water keeps leaking from a tap in the washroom and the floor is slippery. This has happened several times.",
                                  "location": "Nilgiri washroom", "hazardStillActive": True, "injury": False, "severity": "Medium", "immediateActions": None}),
    ("6 Hostel smoke", {"appType": "Fire / Smoke", "description": "Smoke was coming from a room in the hostel. The fire alarm rang and the smoke has stopped now.",
                        "location": "hostel room", "hazardStillActive": False, "injury": False, "severity": "High", "immediateActions": None}),
    ("7 Mess LPG smell", {"appType": "Gas Leak", "description": "Strong smell of LPG gas near the gas stove, the smell is still there.",
                          "location": "Mess kitchen", "hazardStillActive": True, "injury": False, "severity": "High", "immediateActions": None}),
    ("8 Football injury", {"appType": "Injury", "description": "A player fell during the football match and is on the ground holding his leg.",
                           "location": "Football ground", "hazardStillActive": None, "injury": True, "severity": "High", "immediateActions": None}),
    ("9 Broken classroom furniture", {"appType": "Equipment Damage", "description": "Two chairs and one bench in the classroom are broken.",
                                      "location": "Himalaya classroom", "hazardStillActive": False, "injury": False, "severity": "Low", "immediateActions": None}),
    ("10 Chemical spill", {"appType": "Spill", "description": "A bottle of acid broke and the chemical spilled on the floor, the liquid is still spreading.",
                           "location": "Chemistry lab", "hazardStillActive": True, "injury": False, "severity": "High", "immediateActions": None}),
    ("11 Blocked fire exit", {"appType": "Other", "description": "The fire exit is blocked by stored boxes.",
                              "location": "Nilgiri corridor", "hazardStillActive": None, "injury": None, "severity": "Medium", "immediateActions": None}),
    ("12 Network and storage outage", {"appType": "Equipment Failure", "description": "The network is down and the storage system is not reachable, many services are unavailable.",
                                       "location": "KRB network room", "hazardStillActive": True, "injury": False, "severity": "High", "immediateActions": None}),
]
EXPECTED_TYPES = ["CLASSROOM_FIRE", "COMPUTER_FIRE", "COOLING_FAILURE", "WATER_NEAR_POWER", "WATER_LEAK", "HOSTEL_FIRE", "LPG_SMELL",
                  "SPORTS_INJURY", "DAMAGED_FURNITURE", "CHEMICAL_SPILL", "BLOCKED_EXIT", "NETWORK_OUTAGE"]
LIMITS = {I: 3, O: 3, R: 3, P: 2}
FORB_WORDS = re.compile(r"\b(medicines?|medications?|medicate|tablets?|pills?|capsule|syrup|ointment|cream|painkillers?|antibiotics?|paracetamol|"
                        r"ibuprofen|aspirin|antacid|drugs?|injection|vaccine|doses?|dosage|mg|ml|milligrams?|treats?|treated|treating|treatments?|"
                        r"therapy|remed(?:y|ies)|cures?|heal|prescribe|prescribed|prescription|diagnos\w*|fracture|fractured|sprain|sprained|"
                        r"strain|concussion|dislocat\w*|internal bleeding|heart attack|stroke|infection|fever|poisoning|home remedy|ice pack|"
                        r"apply ice|apply pressure|bandage|splint|tourniquet|massage|drink water|take rest|you may have)\b|you have", re.I)
UNSAFE = re.compile(r"\b(enter|entering|go into|re-enter|fight|fighting|extinguish\w*|put out|unplug|plug in|switch off|switch on|turn off|"
                    r"turn on|flip|reset|shut off|close the valve|restart\w*|reboot|power on|powered on|clean up|cleanup|mop|wipe|neutrali[sz]e|"
                    r"absorb|handle|operate)\b|(?<!not )\btouch\b|\b(dispatched|has been notified|on the way)\b", re.I)


def build_scenarios():
    out = []
    for (name, report), etype in zip(SCENARIOS, EXPECTED_TYPES):
        n = normalize(report)
        assert n["type"] == etype, (name, n)
        sev = report["severity"].upper()
        inc = {"incidentId": "INC-2026-%04d" % (len(out) + 1), "type": n["type"], "severity": sev, "urgency": None, "status": n["status"],
               "locationId": n["locationId"], "locationType": n["locationType"], "affectedAssets": n["assets"],
               "activeHazards": n["hazards"], "peopleAffected": None, "operationalEffects": n["operationalEffects"],
               "evidenceConfidence": 0.9, "timestamp": "2026-10-11T10:00:00+05:30", "userConfirmed": True, "injury": report["injury"],
               "locationText": report["location"]}
        sim_in = dict(inc, hazards=n["hazards"], assets=n["assets"])
        ph, arr, _ = simulate(sim_in)
        adm, _, _ = simulate(sim_in, admin=True)
        for p, lst in ph.items():
            assert len(lst) <= LIMITS[p], (name, p, lst)
        dependents = {a for a, _b in DEPENDENCIES}
        shown = [s for p in (I, O, R, P) for s in ph[p]]
        must = ["rec:" + s for s in shown if s not in dependents]
        hidden = ["rec:" + s for p in (I, O, R, P) for s in adm[p] if s not in shown]
        arr_show = [a for a in arr if not ARR_REQ[a]]
        arr_supp = [a for a in arr if ARR_REQ[a]]
        assert len(arr_show) <= 2, (name, arr_show)
        entry = {"name": name, "report": report,
                 "expected": {"type": n["type"], "locationType": n["locationType"], "locationId": n["locationId"], "status": n["status"],
                              "assets": n["assets"], "hazards": n["hazards"], "operationalEffects": n["operationalEffects"]},
                 "incident": inc, "mustInclude": must}
        dep_shown = ["rec:" + s for s in shown if s in dependents]
        if dep_shown:
            entry["optionalDependents"] = dep_shown
        if hidden:
            entry["mustNotShowReporter"] = hidden
            entry["adminOnlyPresent"] = hidden
        if arr_show:
            entry["mustIncludeArrangements"] = ["arrangement:" + a for a in arr_show]
        if arr_supp:
            entry["suppressedArrangements"] = ["arrangement:" + a for a in arr_supp]
        entry["note"] = ("Phase candidate counts (reporter) " + ", ".join("%s=%d" % (p, len(ph[p])) for p in (I, O, R, P)) +
                         "; all within output limits so mustInclude is independent of ranking. optionalDependents have a DEPENDS_ON "
                         "prerequisite and are not required.")
        if name.startswith("5"):
            entry["historyDependent"] = ["rec:inspect-shared-plumbing"]
        out.append(entry)
    return out


ARR_REQ = {s: bool(req) for s, _t, _x, _tm, req in ARRANGEMENTS}

# ---------------------------------------------------------------------------------------------------------------- self-check
def self_check():
    errs = []
    ids = [n["id"] for n in nodes]
    if len(ids) != len(set(ids)):
        errs.append("duplicate node ids")
    eids = [e["id"] for e in edges]
    if len(eids) != len(set(eids)):
        errs.append("duplicate edge ids")
    src_ids = {s["id"] for s in SOURCES}
    for e in edges:
        if e["sourceId"] not in nodes_by_id:
            errs.append("dangling source " + e["id"])
        if e["relation"] == "SUPPORTED_BY":
            if e["targetId"] not in src_ids:
                errs.append("SUPPORTED_BY target not a source " + e["id"])
        elif e["targetId"] not in nodes_by_id:
            errs.append("dangling target " + e["id"])
        if e["sourceRef"] and e["sourceRef"] not in src_ids:
            errs.append("unknown sourceRef " + e["id"])
        if not 0 <= e["priority"] <= 100:
            errs.append("priority out of range " + e["id"])
    acts = [n for n in nodes if n["type"] == "ACTION"]
    recs = [n for n in nodes if n["type"] == "RECOMMENDATION"]
    for a in acts:
        mine = [r for r in recs if r["properties"]["actionId"] == a["id"]]
        if len(mine) != 1:
            errs.append("action without exactly one rec " + a["id"])
        else:
            sb = [e for e in edges if e["sourceId"] == mine[0]["id"] and e["relation"] == "SUPPORTED_BY"]
            if len(sb) != 1:
                errs.append("rec without SUPPORTED_BY " + mine[0]["id"])
        if not [e for e in edges if e["sourceId"] == a["id"] and e["relation"] == "OWNED_BY"]:
            errs.append("action without owner " + a["id"])
    for r in recs:
        if r["properties"]["actionId"] not in nodes_by_id:
            errs.append("rec action missing " + r["id"])
    keys = {}
    for a in acts:
        keys.setdefault(a["properties"]["canonicalKey"], []).append(a["id"])
    # DEPENDS_ON cycles
    g = {}
    for e in edges:
        if e["relation"] == "DEPENDS_ON":
            g.setdefault(e["sourceId"], []).append(e["targetId"])
    state = {}

    def dfs(u):
        state[u] = 1
        for v in g.get(u, []):
            if state.get(v) == 1 or (state.get(v) is None and dfs(v)):
                return True
        state[u] = 2
        return False
    if any(state.get(u) is None and dfs(u) for u in list(g)):
        errs.append("DEPENDS_ON cycle")
    for a, b in DEPENDENCIES:  # a type that triggers a must trigger its prerequisite b
        for t, ss in TRIG_EDGES.items():
            if a in ss and b not in ss:
                errs.append("dependency %s->%s not triggered together for %s" % (a, b, t))
    # text scan
    texts = []
    for n in nodes:
        p = n["properties"]
        for k in ("canonicalText", "text", "title", "description"):
            if k in p:
                texts.append((n["id"], p[k]))
        texts.append((n["id"] + "#name", n["name"]))
    for sid, t in texts:
        if re.search(r"\d", t):
            errs.append("digit in text " + sid)
        if FORB_WORDS.search(t):
            errs.append("forbidden medical word in " + sid)
        if UNSAFE.search(t):
            errs.append("unsafe phrase in " + sid)
    for a in acts:
        p = a["properties"]
        if p["userCanPerform"] and (p["requiresAuthorization"] or p["canonicalText"].startswith("Ask ")):
            errs.append("userCanPerform action must be self protective " + a["id"])
        if not p["userCanPerform"] and not (p["requiresAuthorization"] and p["canonicalText"].startswith("Ask ")):
            errs.append("operational action must be 'Ask ...' and require authorization " + a["id"])
        if not p["canonicalText"].endswith(".") or p["canonicalText"].count(".") != 1:
            errs.append("canonicalText must be one sentence " + a["id"])
    for n in nodes:
        if n["type"] == "TEMPORARY_ARRANGEMENT" and not n["properties"]["requirements"]:
            if re.search(r"\b(room \w+ \d|capacity)\b", n["properties"]["text"]):
                errs.append("generic arrangement names a room " + n["id"])
    # normalization coverage and >=2 approved recommendations for every type (status ACTIVE and UNKNOWN, nothing else known)
    rule_codes = {r["code"] for r in TYPE_RULES}
    for t in TYPE_CODES:
        if t not in rule_codes:
            errs.append("no normalization rule for " + t)
        for st in ("ACTIVE", "UNKNOWN"):
            inc = {"type": t, "status": st, "locationType": None, "assets": [], "hazards": [], "operationalEffects": [], "injury": False}
            ph, _, _ = simulate(inc)
            n_ = sum(len(v) for v in ph.values())
            if n_ < 2:
                errs.append("type %s status %s has only %d approved recommendations" % (t, st, n_))
    code_sets = {"INCIDENT_TYPE": set(TYPE_CODES), "LOCATION_TYPE": {c for c, _, _ in LOCATION_TYPES}, "ASSET_TYPE": {c for c, _ in ASSET_TYPES},
                 "HAZARD": {h[0] for h in HAZARDS}}
    for key, valid in (("locationTypes", code_sets["LOCATION_TYPE"]), ("assets", code_sets["ASSET_TYPE"]), ("hazards", code_sets["HAZARD"])):
        for r in NORMALIZATION[key]:
            if r["code"] not in valid:
                errs.append("normalization %s code unknown %s" % (key, r["code"]))
    for r in BUILDING_RULES:
        if r["code"] not in nodes_by_id:
            errs.append("building rule target missing " + r["code"])
    for e in edges:
        c = e["conditions"]
        for k, valid in (("incidentTypes", code_sets["INCIDENT_TYPE"]), ("locationTypes", code_sets["LOCATION_TYPE"]),
                         ("assetTypes", code_sets["ASSET_TYPE"]), ("hazardsAny", code_sets["HAZARD"])):
            for v in c.get(k, []):
                if v not in valid:
                    errs.append("unknown %s %s in %s" % (k, v, e["id"]))
    ep = {n["properties"]["code"] for n in nodes if n["type"] == "INCIDENT_TYPE"}
    for n in nodes:
        if n["type"] == "HISTORICAL_PATTERN":
            for t in n["properties"]["incidentTypes"]:
                if t not in ep:
                    errs.append("pattern type unknown " + t)
    if "sop:demo-seed:v1" not in src_ids:
        errs.append("demo source missing")
    draft = [r for r in recs if r["properties"]["approvalStatus"] == "DRAFT"]
    for r in draft:
        if r["properties"]["sourceId"] == DEMO:
            errs.append("draft rec must cite a placeholder source " + r["id"])
    for r in recs:
        if r["properties"]["approvalStatus"] == "APPROVED" and r["properties"]["sourceId"] != DEMO:
            errs.append("approved rec must cite the demo source " + r["id"])
    return errs, {"nodes": len(nodes), "edges": len(edges), "sources": len(SOURCES), "incident types": len(TYPE_CODES),
                  "actions": len(acts), "recommendations": len(recs), "draft recommendations": len(draft),
                  "arrangements": len([n for n in nodes if n["type"] == "TEMPORARY_ARRANGEMENT"]),
                  "patterns": len([n for n in nodes if n["type"] == "HISTORICAL_PATTERN"]), "teams": len(TEAMS),
                  "type rules": len(TYPE_RULES)}


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def main():
    errs, counts = self_check()
    scenarios = []
    try:
        scenarios = build_scenarios()
    except AssertionError as ex:
        errs.append("scenario assertion failed: %r" % (ex.args,))
    for e in errs:
        print("ERROR:", e)
    if errs:
        sys.exit(1)
    dump(PACK_PATH, PACK)
    dump(SCEN_PATH, scenarios)
    print("OK", json.dumps(counts))
    for s in scenarios:
        print(s["name"], "->", s["expected"]["type"], s["expected"]["status"], "must:", len(s["mustInclude"]), s["note"].split(";")[0])


if __name__ == "__main__":
    main()
