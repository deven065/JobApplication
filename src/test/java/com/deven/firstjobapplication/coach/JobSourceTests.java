package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.JobRequirement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobSourceTests {
    @Test void sitesWithoutPublicApisAreRecognised() {
        assertThat(JobDiscoveryService.blocksAutomatedReading("www.linkedin.com")).isEqualTo("LinkedIn");
        assertThat(JobDiscoveryService.blocksAutomatedReading("in.indeed.com")).isEqualTo("Indeed");
        assertThat(JobDiscoveryService.blocksAutomatedReading("indeed.co.in")).isEqualTo("Indeed");
        assertThat(JobDiscoveryService.blocksAutomatedReading("www.naukri.com")).isEqualTo("Naukri");
        assertThat(JobDiscoveryService.blocksAutomatedReading("careers.example.com")).isNull();
        assertThat(JobDiscoveryService.blocksAutomatedReading("notlinkedin.com")).isNull();
    }

    @Test void boardJobLinksAreRecognised() {
        assertThat(JobBoardService.recognise("https://boards.greenhouse.io/stripe/jobs/6042172")).get()
                .isEqualTo(new JobBoardService.BoardJobLink("GREENHOUSE", "stripe", "6042172"));
        assertThat(JobBoardService.recognise("https://job-boards.greenhouse.io/airbnb/jobs/123?gh_src=x")).get()
                .extracting(JobBoardService.BoardJobLink::board).isEqualTo("airbnb");
        assertThat(JobBoardService.recognise("https://jobs.lever.co/palantir/10dfc8bc-99ad-4ca2-ab76-853cb90a92c2")).get()
                .isEqualTo(new JobBoardService.BoardJobLink("LEVER", "palantir", "10dfc8bc-99ad-4ca2-ab76-853cb90a92c2"));
        assertThat(JobBoardService.recognise("https://jobs.ashbyhq.com/notion/e9bf7ffa-9cf2-4360-a2e4-e9fa66859927")).get()
                .extracting(JobBoardService.BoardJobLink::source).isEqualTo("ASHBY");
        assertThat(JobBoardService.recognise("https://www.linkedin.com/jobs/view/4012345678")).isEmpty();
        assertThat(JobBoardService.recognise("https://stripe.com/jobs/search?gh_jid=6042172")).isEmpty();
    }

    @Test void linkedInPostingNeedsPastedTextButKeepsItsLink() {
        JobDiscoveryService jobs = new JobDiscoveryService();
        String url = "https://www.linkedin.com/jobs/view/4012345678";
        assertThatThrownBy(() -> jobs.discover("Java Developer", "Example", url, ""))
                .hasMessageContaining("LinkedIn doesn't allow automated reading");

        JobRequirement job = jobs.discover("Java Developer", "Example", url, "We need Java and SQL experience &amp; testing.");
        assertThat(job.sourceUrl()).isEqualTo(url);
        assertThat(job.requiredSkills()).contains(Skill.JAVA, Skill.SQL);
        assertThat(job.sourceExcerpt()).contains("& testing").doesNotContain("&amp;");
    }
}
