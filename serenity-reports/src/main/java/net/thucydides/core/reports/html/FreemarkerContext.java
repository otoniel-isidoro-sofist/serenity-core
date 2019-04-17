package net.thucydides.core.reports.html;

import com.google.common.base.Splitter;
import net.serenitybdd.core.buildinfo.BuildInfoProvider;
import net.serenitybdd.core.buildinfo.BuildProperties;
import net.serenitybdd.core.reports.styling.TagStylist;
import net.serenitybdd.reports.model.*;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.issues.IssueTracking;
import net.thucydides.core.model.NumericalFormatter;
import net.thucydides.core.model.ReportType;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.model.formatters.ReportFormatter;
import net.thucydides.core.reports.ReportOptions;
import net.thucydides.core.reports.TestOutcomes;
import net.thucydides.core.requirements.RequirementsService;
import net.thucydides.core.requirements.reports.RequirementsOutcomes;
import net.thucydides.core.requirements.reports.ScenarioOutcome;
import net.thucydides.core.requirements.reports.ScenarioOutcomes;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.util.Inflector;
import net.thucydides.core.util.VersionProvider;
import org.joda.time.DateTime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static net.thucydides.core.ThucydidesSystemProperty.REPORT_TAGTYPES;
import static net.thucydides.core.reports.html.HtmlReporter.TIMESTAMP_FORMAT;
import static net.thucydides.core.reports.html.ReportNameProvider.NO_CONTEXT;

/**
 * Created by john on 21/06/2016.
 */
public class FreemarkerContext {

    private final EnvironmentVariables environmentVariables;
    private final RequirementsService requirements;
    private final IssueTracking issueTracking;
    private final String relativeLink;
    private final BuildProperties buildProperties;
    private final TestTag parentTag;
    private final RequirementsService requirementsService;


    public FreemarkerContext(EnvironmentVariables environmentVariables,
                             RequirementsService requirements,
                             IssueTracking issueTracking,
                             String relativeLink,
                             TestTag parentTag) {
        this.environmentVariables = environmentVariables;
        this.requirements = requirements;
        this.issueTracking = issueTracking;
        this.relativeLink = relativeLink;
        buildProperties = new BuildInfoProvider(environmentVariables).getBuildProperties();
        this.parentTag = parentTag;
        this.requirementsService = Injectors.getInjector().getInstance(RequirementsService.class);
    }


    public FreemarkerContext(EnvironmentVariables environmentVariables,
                             RequirementsService requirements,
                             IssueTracking issueTracking,
                             String relativeLink) {
        this(environmentVariables, requirements, issueTracking, relativeLink, TestTag.EMPTY_TAG);
    }

    public Map<String, Object> getBuildContext(TestOutcomes testOutcomes,
                                               ReportNameProvider reportName,
                                               boolean useFiltering) {
        Map<String, Object> context = new HashMap();
        TagFilter tagFilter = new TagFilter(environmentVariables);
        context.put("testOutcomes", testOutcomes);
        context.put("allTestOutcomes", testOutcomes.getRootOutcomes());
        if (useFiltering) {
            context.put("tagTypes", tagFilter.filteredTagTypes(testOutcomes.getTagTypes()));
        } else {
            context.put("tagTypes", testOutcomes.getTagTypes());
        }
        context.put("currentTag", TestTag.EMPTY_TAG);
        context.put("parentTag", parentTag);
        context.put("reportName", reportName);

        context.put("absoluteReportName", new ReportNameProvider(NO_CONTEXT, ReportType.HTML, requirements));

        context.put("reportOptions", new ReportOptions(environmentVariables));
        context.put("timestamp", timestampFrom(new DateTime()));
        context.put("requirementTypes", requirements.getRequirementTypes());
        context.put("leafRequirementType", last(requirements.getRequirementTypes()));
        addFormattersToContext(context);


        VersionProvider versionProvider = new VersionProvider(environmentVariables);
        context.put("serenityVersionNumber", versionProvider.getVersion());
        context.put("buildNumber", versionProvider.getBuildNumberText());
        context.put("build", buildProperties);

        context.put("resultCounts", ResultCounts.forOutcomesIn(testOutcomes));
        context.put("scenarios", ScenarioOutcomes.from(testOutcomes));
        context.put("testCases", executedScenariosIn(testOutcomes));
        context.put("automatedTestCases", automated(executedScenariosIn(testOutcomes)));
        context.put("manualTestCases", manual(executedScenariosIn(testOutcomes)));
        context.put("evidence", EvidenceData.from(testOutcomes));

        context.put("frequentFailures", FrequentFailures.from(testOutcomes).withMaxOf(5));
        context.put("unstableFeatures", UnstableFeatures.from(testOutcomes)
                .withRequirementsFrom(requirementsService)
                .withMaxOf(5));

        List<String> tagTypes = Splitter.on(",")
                .trimResults()
                .splitToList(REPORT_TAGTYPES.from(environmentVariables, "feature"));

        context.put("coverage", TagCoverage.from(testOutcomes)
                .showingTags(requirements.getTagsOfType(tagTypes))
                .forTagTypes(tagTypes));
        context.put("backgroundColor", new BackgroundColor());

        testOutcomes.getOutcomes().forEach(
                testOutcome -> addTags(testOutcome, context, null)
        );
        context.put("tagResults", TagResults.from(testOutcomes).forAllTags());


        return context;
    }

    private void addTags(TestOutcome testOutcome, Map<String, Object> context, String parentTitle) {
        TagFilter tagFilter = new TagFilter(environmentVariables);
        Set<TestTag> filteredTags = (parentTitle != null) ? tagFilter.removeTagsWithName(testOutcome.getTags(), parentTitle) : testOutcome.getTags();
        filteredTags = tagFilter.removeRequirementsTagsFrom(filteredTags);
        context.put("filteredTags", filteredTags);
    }

    private String last(List<String> requirementTypes) {
        return (requirementTypes.size() > 0) ? requirementTypes.get(requirementTypes.size() - 1) : "Feature";
    }

    private List<ScenarioOutcome> automated(List<ScenarioOutcome> executedScenariosIn) {
        return executedScenariosIn.stream().filter(scenarioOutcome -> !scenarioOutcome.isManual()).collect(Collectors.toList());
    }

    private List<ScenarioOutcome> manual(List<ScenarioOutcome> executedScenariosIn) {
        return executedScenariosIn.stream().filter(scenarioOutcome -> scenarioOutcome.isManual()).collect(Collectors.toList());
    }

    private List<ScenarioOutcome> executedScenariosIn(TestOutcomes testOutcomes) {
        return ScenarioOutcomes.from(testOutcomes)
                .stream()
                .filter(scenarioOutcome -> !scenarioOutcome.getType().equalsIgnoreCase("background"))
                .collect(Collectors.toList());
    }


    private void addFormattersToContext(final Map<String, Object> context) {
        Formatter formatter = new Formatter();
        ReportFormatter reportFormatter = new ReportFormatter();
        context.put("formatter", formatter);
        context.put("reportFormatter", reportFormatter);
        context.put("formatted", new NumericalFormatter());
        context.put("inflection", Inflector.getInstance());
        context.put("styling", TagStylist.from(environmentVariables));
        context.put("relativeLink", relativeLink);
        context.put("reportOptions", new ReportOptions(environmentVariables));
    }


    protected String timestampFrom(DateTime startTime) {
        return startTime == null ? "" : startTime.toString(TIMESTAMP_FORMAT);
    }

    public FreemarkerContext withParentTag(TestTag knownTag) {
        return new FreemarkerContext(environmentVariables, requirements, issueTracking, relativeLink, knownTag);
    }
}
