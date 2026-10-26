package com.intland.codebeamer.wiki.plugins;

/**
 * Wiki plug-in discovery bridge for StreamTestRunReusePlugin.
 *
 * Codebeamer's short Wiki syntax resolves plug-ins from the direct
 * com.intland.codebeamer.wiki.plugins package. The implementation remains
 * in the streamreuse subpackage, while this class exposes the expected name.
 */
public class StreamTestRunReusePlugin
        extends com.intland.codebeamer.wiki.plugins.streamreuse.StreamTestRunReusePlugin {
    // No additional implementation is required.
}
