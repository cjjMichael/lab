package com.github.jnthnclt.os.lab.core.guts.api;

import com.github.jnthnclt.os.lab.base.BolBuffer;

/**
 *
 * @author jonathan.colt
 */
public interface AppendEntryStream {

    boolean stream(BolBuffer rawEntry) throws Exception;
}
