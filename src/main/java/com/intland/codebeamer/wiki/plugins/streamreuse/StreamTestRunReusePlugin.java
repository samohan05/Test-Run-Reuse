package com.intland.codebeamer.wiki.plugins.streamreuse;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.velocity.VelocityContext;
import org.springframework.context.ApplicationContext;

import com.ecyrd.jspwiki.WikiContext;
import com.intland.codebeamer.controller.ControllerUtils;
import com.intland.codebeamer.persistence.dto.UserDto;
import com.intland.codebeamer.wiki.plugins.base.AbstractCodeBeamerWikiPlugin;

/**
 * Wiki entry point for Stream Test Run Reuse V1.
 */
public class StreamTestRunReusePlugin extends AbstractCodeBeamerWikiPlugin {

    private static final Logger LOG =
            LogManager.getLogger(StreamTestRunReusePlugin.class);

    private static final String TEMPLATE_NAME =
            "stream-test-run-reuse-plugin.vm";

    @Override
    @SuppressWarnings("rawtypes")
    public String execute(WikiContext context, Map parameters) {
        try {
            StreamReuseParameters parsed =
                    StreamReuseParameters.parse(parameters);

            UserDto user = getUserFromContext(context);
            if (user == null) {
                return errorMessage(
                        "The current Codebeamer user is unavailable.");
            }

            HttpServletRequest request = context.getHttpRequest();
            ApplicationContext applicationContext =
                    ControllerUtils.getSpringDispatcherServletContext(request);

            if (applicationContext == null) {
                return errorMessage(
                        "The Codebeamer application context is unavailable.");
            }

            StreamReuseFacade facade =
                    applicationContext.getBean(StreamReuseFacade.class);

            StreamReuseModels.Report report =
                    facade.prepare(request, user, parsed);

            VelocityContext velocity =
                    getDefaultVelocityContextFromContext(context);

            velocity.put("report", report);
            velocity.put("params", parsed);
            velocity.put("pluginVersion", "1.0.1");

            String output =
                    renderPluginTemplate(TEMPLATE_NAME, velocity);

            return output == null || output.trim().isEmpty()
                    ? errorMessage(
                    "The Velocity template returned empty output.")
                    : output;

        } catch (IllegalArgumentException exception) {
            return errorMessage(exception.getMessage());

        } catch (RuntimeException exception) {
            LOG.error(
                    "STREAM_TEST_RUN_REUSE_PLUGIN_FAILED",
                    exception);

            return errorMessage(
                    "Stream Test Run reuse failed. " +
                            "Check the Codebeamer server log for details.");
        }
    }
    /**
     * Builds a Codebeamer-compatible error block for Wiki output.
     * AbstractCodeBeamerWikiPlugin in the customer release does not
     * expose an errorMessage(String) helper, so the plug-in provides
     * its own local implementation.
     */
    private String errorMessage(String message) {
        return "<div class=\"error\"><strong>Stream Test Run Reuse:</strong> "
                + escapeHtml(message)
                + "</div>";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

}
