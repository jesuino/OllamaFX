package org.fxapps.llmfx.tools.web;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class WebSearchTool {

    private final static String DUCKDUCKGO_SEARCH_URL = "https://duckduckgo.com/html/?q=";

    @Tool("Returns a list of search results for a given query.")
    public Map<String, String> doWebSearch(@P("The user query to be searched on the internet") String query) throws Exception{
        // source: https://medium.com/@sethsubr/fetch-duckduckgo-web-search-results-in-20-lines-of-java-code-3a34ea9da085
        Document doc = null;

        try {
            doc = Jsoup.connect(DUCKDUCKGO_SEARCH_URL + query).get();
            Elements results = doc.getElementById("links").getElementsByClass("results_links");
            var resultMap = new HashMap<String, String>();

            for (Element result : results) {
                var title = result.getElementsByClass("links_main").first().getElementsByTag("a").first().text();
                var snippet = result.getElementsByClass("result__snippet").first().text();
                resultMap.put(title, snippet);
            }
            return resultMap;
        } catch (IOException e) {
            throw e;
        }

    }

}
