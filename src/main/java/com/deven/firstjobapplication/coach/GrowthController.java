package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/** Learning paths, interview prep, resume bullets and the public verified-skills profile. */
@RestController
@RequestMapping("/api")
public class GrowthController {
    private final ResumeService resume;
    private final InterviewService interviews;
    private final ProfileService profiles;
    private final AuthStore auth;

    public GrowthController(ResumeService resume, InterviewService interviews, ProfileService profiles, AuthStore auth) {
        this.resume = resume; this.interviews = interviews; this.profiles = profiles; this.auth = auth;
    }

    @GetMapping("/learning-paths") public List<LearningPath> learningPaths() { return LearningPaths.all(); }
    @GetMapping("/resume") public ResumeDraft resume(@RequestParam String repository) { return resume.draft(repository); }
    @GetMapping("/interview") public InterviewPrep interview(@RequestParam String repository) { return interviews.prepare(repository); }

    /** The signed-in user's own published profile (204 when nothing is published). */
    @GetMapping("/profile") public ResponseEntity<PublicProfile> myProfile() {
        return profiles.find(signedIn().login()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/profile/publish") public PublicProfile publish(@RequestBody PublishRequest body) { return profiles.publish(signedIn(), body.repository()); }
    @PostMapping("/profile/unpublish") public ResponseEntity<Void> unpublish() { profiles.unpublish(signedIn().login()); return ResponseEntity.noContent().build(); }

    /** Anyone can read a published profile. */
    @GetMapping("/public/{login}") public PublicProfile publicProfile(@PathVariable String login) {
        return profiles.find(login).orElseThrow(() -> new NoSuchElementException("This profile has not been published."));
    }
    /** README badge. ?skill=SQL shows one skill; without it the badge shows how many skills are verified. */
    @GetMapping(value = "/public/{login}/badge.svg", produces = "image/svg+xml")
    public ResponseEntity<String> badge(@PathVariable String login, @RequestParam(required = false) String skill) {
        PublicProfile profile = profiles.find(login).orElse(null);
        String value; boolean verified;
        if (skill != null && !skill.isBlank()) {
            String wanted = skill.trim();
            ProfileSkill match = profile == null ? null : profile.skills().stream()
                    .filter(s -> s.skill().name().equalsIgnoreCase(wanted) || s.label().equalsIgnoreCase(wanted)).findFirst().orElse(null);
            verified = match != null;
            value = verified ? match.label() + " ✓ verified" : wanted.replaceAll("[^A-Za-z0-9 +#._-]", "") + " not verified";
        } else {
            int count = profile == null ? 0 : profile.skills().size();
            verified = count > 0;
            value = count + (count == 1 ? " skill verified" : " skills verified");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(ProfileService.badge(value, verified));
    }

    private AuthUser signedIn() {
        String login = GitHubCredentials.login();
        if (login == null) throw new SecurityException("Sign in with GitHub first.");
        return auth.user(login).map(AuthStore.Session::user).orElseThrow(() -> new SecurityException("Sign in with GitHub first."));
    }
    public record PublishRequest(String repository) { }
}
