package org.xianshen.mumirrorb.tarot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

@Component
public class TarotCatalog {
    private final List<CardDefinition> cards;
    private final Map<String, CardDefinition> byId;
    public TarotCatalog(ObjectMapper json) throws IOException {
        try (var stream = new ClassPathResource("tarot/catalog-v1.json").getInputStream()) {
            cards = List.of(json.readValue(stream, CardDefinition[].class));
        }
        byId = cards.stream().collect(Collectors.toUnmodifiableMap(CardDefinition::id, Function.identity()));
        if (activeCards().isEmpty()) throw new IllegalStateException("Tarot catalog must contain active cards");
    }
    public String version() { return "v1"; }
    public List<CardDefinition> activeCards() { return cards.stream().filter(CardDefinition::active).toList(); }
    public CardDefinition byId(String id) {
        var card = byId.get(id);
        if (card == null) throw new IllegalStateException("Unknown persisted tarot card: " + id);
        return card;
    }
    public CatalogView view() { return new CatalogView(version(), activeCards().size(), cards); }
}
