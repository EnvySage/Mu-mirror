package org.xianshen.mumirrorb.tarot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

@Component
public class TarotCatalog {
    private final List<CardDefinition> cards;
    private final Map<String, CardDefinition> byId;
    public TarotCatalog(ObjectMapper json) throws IOException {
        try (var stream = new ClassPathResource("tarot/catalog-v2.json").getInputStream()) {
            cards = List.of(json.readValue(stream, CardDefinition[].class));
        }
        byId = cards.stream().collect(Collectors.toUnmodifiableMap(CardDefinition::id, Function.identity()));
        validateDeck();
    }
    private void validateDeck() {
        var active = activeCards();
        var counts = active.stream().collect(Collectors.groupingBy(CardDefinition::category, Collectors.counting()));
        if (active.size() != 78 || !counts.equals(Map.of("major", 22L, "wands", 14L, "cups", 14L, "swords", 14L, "pentacles", 14L)))
            throw new IllegalStateException("Tarot v2 requires 22 major and 14 cards in each of four suits");
        var ranks = Set.of("ace", "02", "03", "04", "05", "06", "07", "08", "09", "10", "page", "knight", "queen", "king");
        for (String suit : List.of("wands", "cups", "swords", "pentacles")) {
            var expectedIds = ranks.stream().map(rank -> suit + "-" + rank).collect(Collectors.toSet());
            var actualIds = active.stream().filter(card -> suit.equals(card.category())).map(CardDefinition::id).collect(Collectors.toSet());
            if (!actualIds.equals(expectedIds)) throw new IllegalStateException("Incomplete tarot suit: " + suit);
        }
        var majorOrdinals = active.stream().filter(card -> "major".equals(card.category())).map(CardDefinition::ordinal).collect(Collectors.toSet());
        if (!majorOrdinals.equals(java.util.stream.IntStream.range(0, 22).boxed().collect(Collectors.toSet())))
            throw new IllegalStateException("Major arcana must cover ordinals 0 through 21");
    }
    public String version() { return "v2"; }
    public List<CardDefinition> activeCards() { return cards.stream().filter(CardDefinition::active).toList(); }
    public CardDefinition byId(String id) {
        var card = byId.get(id);
        if (card == null) throw new IllegalStateException("Unknown persisted tarot card: " + id);
        return card;
    }
    public CatalogView view() { return new CatalogView(version(), activeCards().size(), cards); }
}
