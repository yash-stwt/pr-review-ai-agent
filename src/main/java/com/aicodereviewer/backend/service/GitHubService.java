package com.aicodereviewer.backend.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class GitHubService {

    private final RestTemplate restTemplate = new RestTemplate();

    public String fetchPullRequestDiff(String token, String owner, String repo, String prNumber) {
        String url = "https://api.github.com/repos/%s/%s/pulls/%s".formatted(owner, repo, prNumber);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(MediaType.parseMediaTypes("application/vnd.github.v3.diff"));
        headers.set("X-GitHub-Api-Version", "2022-11-28");

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            return response.getBody() == null ? "" : response.getBody();
        } catch (HttpStatusCodeException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "GitHub API error: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to fetch PR diff from GitHub", ex);
        }
    }
}
