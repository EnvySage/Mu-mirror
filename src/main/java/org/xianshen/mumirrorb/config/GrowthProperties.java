package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Data @Component @ConfigurationProperties(prefix="mirror.growth")
public class GrowthProperties {
 private List<Axis> defaults = List.of(new Axis("knowledge","学识","学习与理解"),new Axis("courage","勇气","面对困难"),new Axis("action","行动","落实计划"),new Axis("care","体贴","关怀自己与他人"),new Axis("expression","表达","清晰表达"));
 private List<Integer> stages = List.of(0,10,30,60,100);
 private int aiMaxPoints=5, manualMaxPoints=100;
 @Data public static class Axis { private String id,name,description; public Axis(){} public Axis(String id,String name,String description){this.id=id;this.name=name;this.description=description;} }
}
