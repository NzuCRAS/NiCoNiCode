package com.niconicode.agent.tracker.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class RssService {

    public List<SyndEntry> fetchLatestEntries(String rssUrl, int limit) {
        try {
            URL url = URI.create(rssUrl).toURL();
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(url));
            List<SyndEntry> entries = feed.getEntries();
            return entries.subList(0, Math.min(limit, entries.size()));
        } catch (Exception e) {
            log.warn("Failed to fetch RSS: {}", rssUrl, e);
            return Collections.emptyList();
        }
    }
}
