package org.hisp.dhis.tracker.programrule;

/*
 * Copyright (c) 2004-2020, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.api.client.util.Sets;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramInstance;
import org.hisp.dhis.program.ProgramStageInstance;
import org.hisp.dhis.programrule.engine.ProgramRuleEngine;
import org.hisp.dhis.rules.models.RuleEffect;
import org.hisp.dhis.tracker.TrackerIdScheme;
import org.hisp.dhis.tracker.TrackerProgramRuleService;
import org.hisp.dhis.tracker.bundle.TrackerBundle;
import org.hisp.dhis.tracker.converter.TrackerConverterService;
import org.hisp.dhis.tracker.domain.Enrollment;
import org.hisp.dhis.tracker.domain.Event;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Enrico Colasante
 */
@Service
public class DefaultTrackerProgramRuleService
    implements TrackerProgramRuleService
{
    private final ProgramRuleEngine programRuleEngine;

    private final TrackerConverterService<Enrollment, ProgramInstance> enrollmentTrackerConverterService;

    private final TrackerConverterService<Event, ProgramStageInstance> eventTrackerConverterService;

    public DefaultTrackerProgramRuleService(
        @Qualifier( "serviceTrackerRuleEngine" ) ProgramRuleEngine programRuleEngine,
        TrackerConverterService<Enrollment, ProgramInstance> enrollmentTrackerConverterService,
        TrackerConverterService<Event, ProgramStageInstance> eventTrackerConverterService )
    {
        this.programRuleEngine = programRuleEngine;
        this.enrollmentTrackerConverterService = enrollmentTrackerConverterService;
        this.eventTrackerConverterService = eventTrackerConverterService;
    }

    @Override
    @Transactional( readOnly = true )
    public Map<String, List<RuleEffect>> calculateEnrollmentRuleEffects( List<Enrollment> enrollments,
        TrackerBundle bundle )
    {
        return enrollments
            .stream()
            .collect( Collectors.toMap( Enrollment::getEnrollment, e -> {
                ProgramInstance enrollment = enrollmentTrackerConverterService.fromForRuleEngine( bundle.getPreheat(),
                    e );
                return programRuleEngine.evaluate( enrollment, Sets.newHashSet() );
            } ) );
    }

    @Override
    @Transactional( readOnly = true )
    public Map<String, List<RuleEffect>> calculateEventRuleEffects( List<Event> events, TrackerBundle bundle )
    {
        return events
            .stream()
            .collect( Collectors.toMap( Event::getEvent, event -> {
                ProgramInstance enrollment = getEnrollment( bundle, event );
                ProgramStageInstance programStageInstance = eventTrackerConverterService
                    .from( bundle.getPreheat(), event );
                if ( enrollment == null )
                {
                    return programRuleEngine.evaluateProgramEvent( programStageInstance,
                        bundle.getPreheat().get( Program.class, event.getProgram() ) );
                }
                else
                {
                    return programRuleEngine.evaluate( enrollment,
                        programStageInstance,
                        getEventsFromEnrollment( enrollment.getUid(), bundle, events ) );
                }
            } ) );
    }

    private ProgramInstance getEnrollment( TrackerBundle bundle, Event event )
    {
        Optional<Enrollment> bundleEnrollment = bundle.getEnrollments()
            .stream()
            .filter( e -> event.getEnrollment().equals( e.getEnrollment() ) )
            .findAny();
        return bundleEnrollment.isPresent()
            ? enrollmentTrackerConverterService.fromForRuleEngine( bundle.getPreheat(), bundleEnrollment.get() )
            : bundle.getPreheat().getEnrollment( TrackerIdScheme.UID, event.getEnrollment() );
    }

    private Set<ProgramStageInstance> getEventsFromEnrollment( String enrollment, TrackerBundle bundle,
        List<Event> events )
    {
        List<ProgramStageInstance> preheatEvents = bundle.getPreheat().getEvents().values()
            .stream()
            .flatMap( psi -> psi.values().stream() )
            .collect( Collectors.toList() );
        Stream<ProgramStageInstance> programStageInstances = preheatEvents
            .stream()
            .filter( e -> e.getProgramInstance().getUid().equals( enrollment ) );
        Stream<ProgramStageInstance> bundleEvents = events
            .stream()
            .filter( e -> e.getEnrollment().equals( enrollment ) )
            .map( event -> eventTrackerConverterService.from( bundle.getPreheat(), event ) );

        return Stream.concat( programStageInstances, bundleEvents ).collect( Collectors.toSet() );

    }
}
