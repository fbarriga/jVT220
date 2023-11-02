/**
 * jVT220 - Java VT220 terminal emulator.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package nl.lxtreme.jvt220.terminal.swing;


import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Provides a thread-safe character buffer which can append a list of
 * characters, or remove a number of characters from the front.
 * <p>
 * This buffer is designed for atomicity regarding the appending and removal of
 * items. There are no ordering guarantees when multiple threads are
 * concurrently appending, the only thing that is guaranteed is that the append
 * itself is performed atomicly.
 * </p>
 */
final class CharBuffer implements CharSequence
{
  // INNER TYPES

  /**
   * Contains the state of the char buffer itself.
   */
  private static class CharBufferState
  {
    // VARIABLES

    final Integer[] chars;
    final int appendPos;

    // CONSTRUCTORS

    /**
     * Creates a new {@link CharBufferState} instance.
     */
    public CharBufferState( final Integer[] aChars, final int aAppendPos )
    {
      this.chars = aChars;
      this.appendPos = aAppendPos;
    }
  }

  // VARIABLES

  private final AtomicReference<CharBufferState> stateRef;

  // CONSTRUCTORS

  /**
   * Creates a new {@link CharBuffer} instance.
   */
  public CharBuffer()
  {
    this.stateRef = new AtomicReference<CharBufferState>( new CharBufferState( new Integer[10], 0 ) );
  }

  /**
   * Creates a new {@link CharBuffer} instance with a given initial value.
   * 
   * @param aInitialValue
   *          the initial value of this buffer, cannot be <code>null</code>.
   * @throws IllegalArgumentException
   *           in case the given list was <code>null</code>.
   */
  public CharBuffer( final Integer... aInitialValue )
  {
    if ( aInitialValue == null )
    {
      throw new IllegalArgumentException( "InitialValue cannot be null!" );
    }

    Integer[] initialValue = Arrays.copyOf( aInitialValue, aInitialValue.length + 10 );
    int appendPos = aInitialValue.length;

    this.stateRef = new AtomicReference<CharBufferState>( new CharBufferState( initialValue, appendPos ) );
  }

  // METHODS

  /**
   * Appends a given list of characters to this buffer.
   * 
   * @param aChars
   *          the characters to add, cannot be <code>null</code>.
   * @throws IllegalArgumentException
   *           in case the given list was <code>null</code>.
   */
  public void append( final Integer... aChars )
  {
    if ( aChars == null )
    {
      throw new IllegalArgumentException( "Chars cannot be null!" );
    }

    // Two options for concurrency while we're appending:
    // 1) another thread removed data -> append still is valid;
    // 2) another thread also appended new data -> append is still valid.

    CharBufferState curState, newState;

    do
    {
      curState = this.stateRef.get();
      Integer[] curArray = curState.chars;
      int curAppendPos = curState.appendPos;

      if ( ( curAppendPos + aChars.length ) >= curArray.length )
      {
        // Enlarge array...
        curArray = Arrays.copyOf( curArray, curArray.length + aChars.length + 10 );
      }

      System.arraycopy( aChars, 0, curArray, curAppendPos, aChars.length );

      newState = new CharBufferState( curArray, curAppendPos + aChars.length );
    }
    while ( !this.stateRef.compareAndSet( curState, newState ) );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public char charAt( final int aIndex )
  {
    CharBufferState state = this.stateRef.get();
    Integer[] chars = state.chars;
    int appendPos = state.appendPos;

    if ( ( aIndex < 0 ) || ( aIndex >= appendPos ) )
    {
      throw new IndexOutOfBoundsException();
    }

    final Integer integer = chars[aIndex];
    return ( char )( ( integer == null ) ? 0 : integer.intValue() );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int length()
  {
    CharBufferState state = this.stateRef.get();
    return state.appendPos;
  }

  /**
   * Removes all characters until (but not including) the given position.
   * 
   * @param aPosition
   *          the position until which the characters should be removed, with 0
   *          meaning nothing will be removed, 1 meaning the first character
   *          will be removed, and so on.
   */
  public void removeUntil( final int aPosition )
  {
    CharBufferState curState, newState;
    int oldAppendPos = -1;

    // Two options for concurrency while we're removing:
    // __ 1) another thread appends new data -> remove still is valid;
    // __ 2) another thread also removes data:
    // ____ a) it removed data up to our position -> remove still is valid;
    // ____ b) it removed data beyond our position -> our no longer holds.

    do
    {
      curState = this.stateRef.get();

      int position = aPosition;
      int curAppendPos = curState.appendPos;
      Integer[] curArray = curState.chars;

      if ( oldAppendPos >= 0 )
      {
        // CAS failed, find out what the resolution should be...
        if ( oldAppendPos > curAppendPos )
        {
          // Data is removed, do only remove the difference in positions...
          position -= ( oldAppendPos - curAppendPos );
        }

        // In case oldAppendPos < appendPos, there's data appended, which makes
        // our remove still valid. In case oldAppendPos == appendPos, then
        // something strange is going on, as we've lost our CAS, but neither an
        // add, nor an remove is performed. In that case, simply proceed as
        // normal...
      }
      else
      {
        // First time we're in this loop; do a simple bounds check...
        if ( ( position < 0 ) || ( position > curAppendPos ) )
        {
          throw new IndexOutOfBoundsException( "Position cannot be negative or beyond the length of this buffer!" );
        }
      }

      // Initially, position cannot be less than zero (is checked in the above
      // else condition), however, if the CAS fails, we might have updated the
      // position thereby making it possible to let it be negative...
      if ( position <= 0 )
      {
        // Nothing to do...
        return;
      }

      // Perform the actual removal...
      int newSize = Math.max( 0, curArray.length - position );
      Integer[] newArray = new Integer[newSize];
      System.arraycopy( curArray, position, newArray, 0, newSize );

      int newAppendPos = Math.max( 0, curAppendPos - position );

      newState = new CharBufferState( newArray, newAppendPos );

      oldAppendPos = curAppendPos;
    }
    while ( !this.stateRef.compareAndSet( curState, newState ) );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CharSequence subSequence( final int aStart, final int aEnd )
  {
    throw new UnsupportedOperationException();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString()
  {
    CharBufferState state = this.stateRef.get();
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < state.appendPos; i++ )
    {
      int c = state.chars[i].intValue();
      if ( c >= ' ' && c <= '~' )
      {
        sb.append( ( char )c );
      }
      else
      {
        sb.append( "<" ).append( c ).append( ">" );
      }
    }
    return sb.toString();
  }
}
