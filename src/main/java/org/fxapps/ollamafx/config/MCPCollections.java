package org.fxapps.ollamafx.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "mcp")
public interface MCPCollections {    

    Map<String, MCPServerDefinition> servers();

    public interface MCPServerDefinition {
        Optional<List<String>> commands();
        Optional<String> url();
    }
    
}
