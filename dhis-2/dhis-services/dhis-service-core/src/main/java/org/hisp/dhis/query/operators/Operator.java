package org.hisp.dhis.query.operators;

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

import org.hibernate.criterion.Criterion;
import org.hisp.dhis.query.JpaQueryUtils;
import org.hisp.dhis.query.QueryParserException;
import org.hisp.dhis.query.QueryUtils;
import org.hisp.dhis.query.Type;
import org.hisp.dhis.query.Typed;
import org.hisp.dhis.query.planner.QueryPath;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Morten Olav Hansen <mortenoh@gmail.com>
 */
public abstract class Operator<T extends Comparable<? super T>>
{
    protected final String name;

    protected final List<T> args = new ArrayList<>();

    protected final List<Collection<T>> collectionArgs = new ArrayList<>();

    protected final Typed typed;

    protected Class<T> klass;

    protected Type argumentType;

    public Operator( String name, Typed typed )
    {
        this.name = name;
        this.typed = typed;
    }

    public Operator( String name, Typed typed, Collection<T> collectionArg )
    {
        this( name, typed );
        this.argumentType = new Type( collectionArg );
        this.collectionArgs.add( collectionArg );
    }

    public Operator( String name, Typed typed, Collection<T>... collectionArgs )
    {
        this( name, typed );
        this.argumentType = new Type( collectionArgs[0] );
        Collections.addAll( this.collectionArgs, collectionArgs );
    }

    public Operator( String name, Typed typed, T arg )
    {
        this( name, typed );
        this.argumentType = new Type( arg );
        this.args.add( arg );
        validate();
    }

    public Operator( String name, Typed typed, T... args )
    {
        this( name, typed );
        this.argumentType = new Type( args[0] );
        Collections.addAll( this.args, args );
    }

    private void validate()
    {
        for ( Object arg : args )
        {
            if ( !isValid( arg.getClass() ) )
            {
                throw new QueryParserException( "Value `" + args.get( 0 ) + "` of type `" + arg.getClass().getSimpleName() + "` is not supported by this operator." );
            }
        }
    }

    public List<T> getArgs()
    {
        return args;
    }

    public List<Collection<T>> getCollectionArgs()
    {
        return collectionArgs;
    }

    protected <T> T getValue( Class<T> klass, Class<?> secondaryClass, int idx )
    {
        if ( Collection.class.isAssignableFrom( klass ) )
        {
            return QueryUtils.parseValue( klass, secondaryClass, getCollectionArgs().get( idx ) );
        }

        return QueryUtils.parseValue( klass, secondaryClass, args.get( idx ) );
    }

    protected <T> T getValue( Class<T> klass, int idx )
    {
        if ( Collection.class.isAssignableFrom( klass ) )
        {
            return QueryUtils.parseValue( klass, null, getCollectionArgs().get( idx ) );
        }

        return QueryUtils.parseValue( klass, null, args.get( idx ) );
    }

    protected <T> T getValue( Class<T> klass )
    {
        if ( Collection.class.isAssignableFrom( klass ) )
        {
            return QueryUtils.parseValue( klass, null, getCollectionArgs().get( 0 ) );
        }

        return getValue( klass, 0 );
    }

    protected <T> T getValue( Class<T> klass, Class<?> secondaryClass, Object value )
    {
        return QueryUtils.parseValue( klass, secondaryClass, value );
    }

    protected <T> T getValue( Class<T> klass, Object value )
    {
        return QueryUtils.parseValue( klass, value );
    }

    public boolean isValid( Class<?> klass )
    {
        return typed.isValid( klass );
    }

    public abstract Criterion getHibernateCriterion( QueryPath queryPath );

    public abstract <Y> Predicate getPredicate( CriteriaBuilder builder, Root<Y> root, QueryPath queryPath );

    public abstract boolean test( Object value );

    org.hibernate.criterion.MatchMode getMatchMode( org.hisp.dhis.query.operators.MatchMode matchMode )
    {
        switch ( matchMode )
        {
        case EXACT:
            return org.hibernate.criterion.MatchMode.EXACT;
        case START:
            return org.hibernate.criterion.MatchMode.START;
        case END:
            return org.hibernate.criterion.MatchMode.END;
        case ANYWHERE:
            return org.hibernate.criterion.MatchMode.ANYWHERE;
        default:
            return null;
        }
    }

    protected JpaQueryUtils.StringSearchMode getJpaMatchMode( org.hisp.dhis.query.operators.MatchMode matchMode )
    {
        switch ( matchMode )
        {
        case EXACT:
            return JpaQueryUtils.StringSearchMode.EQUALS;
        case START:
            return JpaQueryUtils.StringSearchMode.STARTING_LIKE;
        case END:
            return JpaQueryUtils.StringSearchMode.ENDING_LIKE;
        case ANYWHERE:
            return JpaQueryUtils.StringSearchMode.ANYWHERE;
        default:
            return null;
        }
    }

    @Override
    public String toString()
    {
        return "[" + name + ", args: " + args + "]";
    }
}
