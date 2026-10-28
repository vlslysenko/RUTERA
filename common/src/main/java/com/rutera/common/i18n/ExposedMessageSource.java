package com.rutera.common.i18n;

import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Message source that can hand out the whole merged dictionary of a locale (SMDP
 * ExposedResourceMessageBundleSource), used to serve the frontend its translations as JSON.
 */
public class ExposedMessageSource extends ReloadableResourceBundleMessageSource {

    /** All keys and texts of the locale, including fallbacks to the default bundle, sorted by key. */
    public Map<String, String> dictionary(Locale locale) {
        Properties merged = getMergedProperties(locale).getProperties();
        Map<String, String> result = new TreeMap<>();
        merged.stringPropertyNames().forEach(key -> result.put(key, merged.getProperty(key)));
        return result;
    }
}
