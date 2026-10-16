package com.deven.firstjobapplication.coach;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SkillMatcherTests {
    @Test void sqlIsOnlyDemonstratedWhenQuerySignalExists() {
        assertThat(SkillMatcher.skillsIn("public class Api {} ")).doesNotContain(Skill.SQL);
        assertThat(SkillMatcher.skillsIn("SELECT id FROM applications WHERE active = true")).contains(Skill.SQL);
    }
}
