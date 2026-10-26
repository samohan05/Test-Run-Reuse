// FINAL FULLY STATIC-TYPED, ENTERPRISE-SAFE GROOVY WORKFLOW SCRIPT
// Modular helper functions + strict typing + idempotency guard + full 4-step workflow
// Steps:
// 1. Create Test Run for current Test Case
// 2. Get commonItemId for Test Case
// 3. Get latest Test Run for commonItemId
// 4. Update newly created Test Run with result along with updating a custom field of id 1000.

import org.springframework.context.ApplicationContext

import com.intland.codebeamer.event.BaseEvent
import com.intland.codebeamer.manager.util.ActionData
import com.intland.codebeamer.persistence.dto.TrackerItemDto
import com.intland.codebeamer.event.util.VetoException

import groovy.transform.TypeChecked
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.servlet.http.Cookie

@TypeChecked
class TestRunHelper {

    static Map<String,Object> doRequest(String method, String urlStr, String body, javax.servlet.http.HttpServletRequest req,
                                        String PAT, String BASIC_USER, String BASIC_PASS, boolean USE_SESSION_COOKIE) {

        URL url = new URL(urlStr)
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()

        conn.setRequestMethod(method)
        conn.setConnectTimeout(8000)
        conn.setReadTimeout(20000)
        conn.setRequestProperty("accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")

        // Authentication
        if (PAT != null && PAT.trim().length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + PAT)
        } else if (BASIC_USER != null && BASIC_USER.length() > 0 && BASIC_PASS != null && BASIC_PASS.length() > 0) {
            String basic = (BASIC_USER + ":" + BASIC_PASS).getBytes(StandardCharsets.UTF_8).encodeBase64().toString()
            conn.setRequestProperty("Authorization", "Basic " + basic)
        } else if (USE_SESSION_COOKIE && req != null) {
            Cookie[] cookies = req.getCookies()
            if (cookies != null) {
                for (Cookie c : cookies) {
                    if ("JSESSIONID".equalsIgnoreCase(c.getName())) {
                        conn.setRequestProperty("Cookie", "JSESSIONID=" + c.getValue())
                    }
                }
            }
        }

        // Write body
        if (body != null) {
            conn.setDoOutput(true)
            conn.getOutputStream().withCloseable { os ->
                os.write(body.getBytes(StandardCharsets.UTF_8))
            }
        }

        int status = conn.getResponseCode()
        String text = null
        if (status >= 200 && status < 300) {
            if (conn.getInputStream() != null) {
                text = conn.getInputStream().getText('UTF-8')
            } else {
                text = ""
            }
        } else {
            if (conn.getErrorStream() != null) {
                text = conn.getErrorStream().getText('UTF-8')
            } else {
                text = ""
            }
        }

        Map<String,Object> resp = new LinkedHashMap<String,Object>()
        resp.put("status", (int) status)
        resp.put("text", text != null ? text : "")
        return resp
    }

    static Map<String,Object> createTestRun(Integer testCaseId, Integer trackerId, String baseUrl,
                                            javax.servlet.http.HttpServletRequest req,
                                            String PAT, String BASIC_USER, String BASIC_PASS, boolean USE_SESSION_COOKIE) {

        Map<String,Object> payload = new LinkedHashMap<String,Object>()
        payload.put("runOnlyAcceptedTestCases", false)

        List<Map<String,Object>> tcList = new ArrayList<Map<String,Object>>()
        Map<String,Object> one = new LinkedHashMap<String,Object>()
        one.put("id", testCaseId)
        tcList.add(one)
        payload.put("testCaseIds", tcList)

        String body = JsonOutput.toJson(payload)
        String url = baseUrl + "/cb/api/v3/trackers/" + trackerId + "/testruns"

        Map<String,Object> resp = doRequest("POST", url, body, req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)
        return resp
    }

    static Integer getCommonItemId(Integer testCaseId, String baseUrl,
                                   javax.servlet.http.HttpServletRequest req,
                                   String PAT, String BASIC_USER, String BASIC_PASS, boolean USE_SESSION_COOKIE) {

        String url = baseUrl + "/cb/api/v3/items/" + testCaseId
        Map<String,Object> resp = doRequest("GET", url, null, req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)

        if (resp.get("status") instanceof Integer == false) return null
        int st = (int) resp.get("status")
        if (st < 200 || st >= 300) {
            throw new VetoException("Failed to retrieve item info for testCaseId=" + testCaseId + " status=" + st)
        }

        String txt = (String) resp.get("text")
        Map parsed = (Map) new JsonSlurper().parseText(txt)
        Object cidObj = parsed.get("commonItemId")
        if (cidObj == null) return null
        return (cidObj as Integer)
    }

    static Map<String,Object> getLatestRun(Integer commonItemId, String baseUrl,
                                           javax.servlet.http.HttpServletRequest req,
                                           String PAT, String BASIC_USER, String BASIC_PASS, boolean USE_SESSION_COOKIE) {

        // Payload
        Map<String,Object> rel = new LinkedHashMap<String,Object>()
        rel.put("name", "any")

        Map<String,Object> payload = new LinkedHashMap<String,Object>()
        payload.put("release", rel)

        String body = JsonOutput.toJson(payload)
        String url = baseUrl + "/cb/api/testCases/" + commonItemId + "/testRuns?page=1&pageSize=1"

        Map<String,Object> resp = doRequest("POST", url, body, req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)
        return resp
    }

    static Map<String,Object> updateTestRun(Integer newRunId, Integer testCaseId, Integer commonItemId, String result,
                                            String baseUrl, javax.servlet.http.HttpServletRequest req,
                                            String PAT, String BASIC_USER, String BASIC_PASS, boolean USE_SESSION_COOKIE) {

        Map<String,Object> tcRef = new LinkedHashMap<String,Object>()
        tcRef.put("id", testCaseId)

        Map<String,Object> updateInner = new LinkedHashMap<String,Object>()
        updateInner.put("conclusion", "Updated through API")
        updateInner.put("result", result)
        updateInner.put("runTime", (int) 0)
        updateInner.put("testCaseReference", tcRef)

        List<Map<String,Object>> upList = new ArrayList<Map<String,Object>>()
        upList.add(updateInner)

        Map<String,Object> payload = new LinkedHashMap<String,Object>()
        payload.put("parentResultPropagation", true)
        payload.put("updateRequestModels", upList)

        String body = JsonOutput.toJson(payload)
        String url = baseUrl + "/cb/api/v3/testruns/" + newRunId

        return doRequest("PUT", url, body, req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)
    }
	
	
	static Map<String,Object> getItemFields(
			Integer lastRunId,
			String baseUrl,
			javax.servlet.http.HttpServletRequest req,
			String PAT,
			String BASIC_USER,
			String BASIC_PASS,
			boolean USE_SESSION_COOKIE
	) {
		String url = baseUrl + "/cb/api/v3/items/" + lastRunId + "/fields"
		return doRequest("GET", url, null, req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)
	}

}


// ========================================================================
// MAIN EXECUTE METHOD
// ========================================================================
@TypeChecked
void execute(ApplicationContext context,
             BaseEvent<?, TrackerItemDto, ActionData> event) {

    final String REQ_FLAG = "cb.testrun.created"
    def req = event.getRequest()
	
    if (req != null && Boolean.TRUE.equals(req.getAttribute(REQ_FLAG))) {
        return
    }

    TrackerItemDto currentItem = event.getSecondarySource()
    if (currentItem == null) {
        return
    }

    Integer testCaseId = currentItem.getId()

    // --- Configuration: override these via environment variables (see README.md) ---
    final Integer testRunTrackerId = Integer.valueOf(System.getenv("CB_TEST_RUN_TRACKER_ID") ?: "107933")
    final String baseUrl = System.getenv("CB_BASE_URL") ?: "http://localhost:8080"
    final String PAT = System.getenv("CB_PAT") ?: ""
    final String BASIC_USER = System.getenv("CB_USER") ?: ""
    final String BASIC_PASS = System.getenv("CB_PASS") ?: ""
    final boolean USE_SESSION_COOKIE = false

    // STEP 1 — CREATE TEST RUN
    Map<String,Object> createResp = TestRunHelper.createTestRun(testCaseId, testRunTrackerId, baseUrl,
            req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)

    int cst = (int) createResp.get("status")
    if (cst < 200 || cst >= 300) {
        throw new VetoException("Failed to create Test Run. status=" + cst + " text=" + createResp.get("text"))
    }

    Map createJson = (Map) new JsonSlurper().parseText((String) createResp.get("text"))
    Integer newRunId = (Integer) createJson.get("id")
    if (newRunId == null) {
        throw new VetoException("Could not extract new test run ID from response.")
    }

    // STEP 2 — GET COMMON ITEM ID
    Integer commonItemId = TestRunHelper.getCommonItemId(testCaseId, baseUrl,
            req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)

    if (commonItemId == null) {
        throw new VetoException("commonItemId missing for testCase=" + testCaseId)
    }

    // STEP 3 — GET LATEST RUN FOR COMMON ITEM
    Map<String,Object> lastRunResp = TestRunHelper.getLatestRun(commonItemId, baseUrl,
            req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)

    int lrst = (int) lastRunResp.get("status")
    if (lrst < 200 || lrst >= 300) {
        throw new VetoException("Failed to retrieve latest run. status=" + lrst + " text=" + lastRunResp.get("text"))
    }

    Map lastRunJson = (Map) new JsonSlurper().parseText((String) lastRunResp.get("text"))
	List runData = (List) lastRunJson.get("testRuns")

	if (runData == null || runData.isEmpty()) {
		throw new VetoException("No previous runs found for commonItemId=" + commonItemId)
	}

	Map lastRun = (Map) runData.get(0)
	Integer lastRunId = (Integer) lastRun.get("id")     // <-- REQUIRED

	Map resultObj = (Map) lastRun.get("result")
	String lastRunResult = resultObj != null ? resultObj.get("name").toString().toUpperCase() : "PASSED"
	
	
	
			// ========================================================================
		// STEP 3.5 — COPY Test Step Results (2000000) into newRunId
		// ========================================================================

			
		Map<String,Object> fieldsResp = TestRunHelper.getItemFields(
				lastRunId, baseUrl, req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)
				
		// --- GET fields ---
		
		Map<String,Object> fieldsJson =
				(Map<String,Object>) new JsonSlurper().parseText((String) fieldsResp.get("text"))

		// --- Extract Test Step Results from readOnlyTableFields ---
		Map<String,Object> stepTable = null
		List<Object> roTables = (List<Object>) fieldsJson.get("readOnlyTableFields")
		
		if (roTables != null) {
			for (Object o : roTables) {
				if (o instanceof Map) {
					Map<String,Object> tv = (Map<String,Object>) o
					if (tv.get("fieldId") instanceof Integer &&
						((Integer) tv.get("fieldId")) == 2000000) {
						stepTable = tv
						break
					}
				}
			}
		}

		if (stepTable == null) {
			throw new VetoException("STEP 3.5: fieldId 2000000 not found in readOnlyTableFields")
		}

		// --- DEEP-CLEAN + CLONE ROWS EXACTLY LIKE example.json ---
		List<List<Map<String,Object>>> cleanRows =
				new ArrayList<List<Map<String,Object>>>()

		List<Object> srcRows = (List<Object>) stepTable.get("values")
		if (srcRows != null) {
			for (Object rowObj : srcRows) {

				List<Map<String,Object>> cleanRow =
						new ArrayList<Map<String,Object>>()

				for (Object cellObj : (List<Object>) rowObj) {
					Map<String,Object> cell = (Map<String,Object>) cellObj

					Map<String,Object> c = new LinkedHashMap<String,Object>()
					c.put("fieldId", cell.get("fieldId"))
					c.put("type", cell.get("type"))

					if (cell.containsKey("value")) {
						c.put("value", cell.get("value"))
					} else if (cell.containsKey("values")) {
						List<Map<String,Object>> vlist = new ArrayList<Map<String,Object>>()
						for (Object vo : (List<Object>) cell.get("values")) {
							Map<String,Object> vm = (Map<String,Object>) vo
							Map<String,Object> vv = new LinkedHashMap<String,Object>()
							vv.put("id", vm.get("id"))
							vv.put("type", vm.get("type"))
							vlist.add(vv)
						}
						c.put("values", vlist)
					}

					cleanRow.add(c)
				}

				cleanRows.add(cleanRow)
			}
		}

	 // STEP 3.5 — Determine child run row ID correctly
		Integer childRunId = null

		try {
			Map<String,Object> probeResp = TestRunHelper.doRequest(
					"GET",
					baseUrl + "/cb/api/v3/items/" + newRunId,
					null,
					req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE
			)

			if ((int)probeResp.get("status") >= 200 &&
				(int)probeResp.get("status") < 300) {

				Map probeJson = (Map) new JsonSlurper().parseText(
						(String) probeResp.get("text"))

				// children is ALWAYS a LIST, not a MAP
				List<Object> children = (List<Object>) probeJson.get("children")

				if (children != null) {

					// Find the row matching this testCaseId
					for (Object childObj : children) {
						if (childObj instanceof Map) {
							Map<String,Object> child = (Map<String,Object>) childObj

							// Test Case Run rows store the testCase ID inside customFields table
							// but itemId of the child is enough
							if (child.get("id") instanceof Integer) {
								// If you have one testcase per run, the first is correct
								childRunId = (Integer) child.get("id")
								break
							}
						}
					}
				}
			}

		} catch (Throwable t) {
			// ignore, fallback below
		}

		// Fallback if no child found (single-testcase test runs)
		if (childRunId == null) {
			childRunId = newRunId
		}

		// --- BUILD PUT PAYLOAD ---
		Map<String,Object> putBody = new LinkedHashMap<String,Object>()
		List<Map<String,Object>> tv = new ArrayList<Map<String,Object>>()

		Map<String,Object> tbl = new LinkedHashMap<String,Object>()
		tbl.put("fieldId", 2000000)
		tbl.put("name", "Test Step Results")
		tbl.put("type", "TableFieldValue")
		tbl.put("values", cleanRows)

		tv.add(tbl)
		putBody.put("tableValues", tv)
		// --- EXECUTE PUT ---
		Map<String,Object> putResp = TestRunHelper.doRequest(
				"PUT",
				baseUrl + "/cb/api/v3/items/" + childRunId + "/fields?quietMode=false",
				JsonOutput.toJson(putBody),
				req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE)
		
				
	// ========================================================================
	// STEP 4 — Update the Test Case Run ROW via the PARENT run id
	//          (result + Test Run Evidence on the row)
	// ========================================================================

	/*
	 * 1) Build updateRequestModels[0]:
	 *      - result / conclusion / runTime / testCaseReference
	 *      - row-level customFields: ChoiceFieldValue<TrackerItemReference>
	 *        IMPORTANT: each value needs {"id": ..., "type": "TrackerItemReference"}
	 */
	Map<String,Object> tcRef = new LinkedHashMap<>()
	tcRef.put("id", testCaseId)

	Map<String,Object> updateInner = new LinkedHashMap<>()
	updateInner.put("conclusion", "Updated through API")
	updateInner.put("result", lastRunResult)                 // e.g. "PASSED"
	updateInner.put("runTime", (int) 0)
	updateInner.put("testCaseReference", tcRef)

	// ---- row-level "Test Run Evidence" custom field payload ----
	Map<String,Object> evidenceField = new LinkedHashMap<>()
	evidenceField.put("fieldId", 1000)                       // REQUIRED
	evidenceField.put("name", "Test Run Evidence")           // EXACT internal name - REQUIRED
	evidenceField.put("type", "ChoiceFieldValue")            // value model discriminator - REQUIRED

	List<Map<String,Object>> evValues = new ArrayList<>()
	Map<String,Object> evRef = new LinkedHashMap<>()
	evRef.put("id", lastRunId)                               // the original run you want to reference
	evRef.put("type", "TrackerItemReference")                // REQUIRED by ChoiceFieldValue<TrackerItemReference>
	evValues.add(evRef)

	evidenceField.put("values", evValues)
	List<Map<String,Object>> rowCustomFields = new ArrayList<>()
	rowCustomFields.add(evidenceField)

	updateInner.put("customFields", rowCustomFields)

	// ---- root payload for /testruns/{newRunId} ----
	List<Map<String,Object>> updateModels = new ArrayList<>()
	updateModels.add(updateInner)

	Map<String,Object> updatePayload = new LinkedHashMap<>()
	updatePayload.put("parentResultPropagation", true)
	updatePayload.put("updateRequestModels", updateModels)

	/*
	 * 3) Execute PUT on /testruns/{newRunId}
	 *    This updates the row (result + row custom fields) that matches testCaseReference.id
	 */
	Map<String,Object> rowResp = TestRunHelper.doRequest(
			"PUT",
			baseUrl + "/cb/api/v3/testruns/" + newRunId,
			groovy.json.JsonOutput.toJson(updatePayload),
			req, PAT, BASIC_USER, BASIC_PASS, USE_SESSION_COOKIE
	)

	int rowStatus = (int) rowResp.get("status")
	if (rowStatus < 200 || rowStatus >= 300) {
		throw new com.intland.codebeamer.event.util.VetoException(
			"Step 4 failed (row update via parent). status=" + rowStatus +
			" text=" + rowResp.get("text")
		)
	}

    // MARK AS COMPLETED (PREVENT DOUBLE EXECUTION)
    if (req != null) {
        req.setAttribute(REQ_FLAG, Boolean.TRUE)
    }

    event.getRequest().setAttribute("message",
            "SUCCESS: Created Test Run #" + newRunId +
                    ", copied result '" + lastRunResult + "' from commonItemId=" + commonItemId)
}


execute(applicationContext, event)