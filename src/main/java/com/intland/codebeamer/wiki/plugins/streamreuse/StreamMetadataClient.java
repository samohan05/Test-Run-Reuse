package com.intland.codebeamer.wiki.plugins.streamreuse;
import jakarta.servlet.http.HttpServletRequest;
public interface StreamMetadataClient {
 StreamReuseModels.EntityInfo findStream(HttpServletRequest request,int streamId);
 StreamReuseModels.EntityInfo resolveTrackerVariant(HttpServletRequest request,int streamId,int sourceTrackerId);
}
