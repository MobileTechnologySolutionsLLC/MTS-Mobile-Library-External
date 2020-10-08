//
//  MulticastDelegate.swift
//  MulticastDelegateDemo
//
//  Created by Joao Nunes on 28/12/15.
//  Copyright © 2015 Joao Nunes. All rights reserved.
//  MIT License https://github.com/jonasman/MulticastDelegate/blob/master/LICENSE
//

import Foundation

/**
 *  `MulticastDelegate` lets you easily create a "multicast delegate" for a given protocol or class.
 */
open class MulticastDelegate<T> {
    
    /// The delegates hash table.
    fileprivate var delegates: NSHashTable<AnyObject>
    
    /**
     *  Use this method to initialize a new `MulticastDelegate` specifying whether delegate references should be weak or
     *  strong.
     *
     *  - parameter strongReferences: Whether delegates should be strongly referenced, false by default.
     *
     *  - returns: A new `MulticastDelegate` instance
     */
    public init(strongReferences: Bool = false) {
        
        delegates = strongReferences ? NSHashTable() : NSHashTable.weakObjects()
    }
    
    /**
     *  Use this method to initialize a new `MulticastDelegate` specifying the storage options yourself.
     *
     *  - parameter options: The underlying storage options to use
     *
     *  - returns: A new `MulticastDelegate` instance
     */
    public init(options: NSPointerFunctions.Options) {
        delegates = NSHashTable(options: options, capacity: 0)
    }
    
    /**
     *  Use this method to add a delelgate.
     *
     *  Alternatively, you can use the `+=` operator to add a delegate.
     *
     *  - parameter delegate:  The delegate to be added.
     */
    open func addDelegate(_ delegate: T) {
        delegates.add((delegate as AnyObject))
    }
    
    /**
     *  Use this method to remove a previously-added delegate.
     *
     *  Alternatively, you can use the `-=` operator to add a delegate.
     *
     *  - parameter delegate:  The delegate to be removed.
     */
    open func removeDelegate(_ delegate: T) {
        delegates.remove((delegate as AnyObject))
    }
    
    /**
     *  Use this method to invoke a closure on each delegate.
     *
     *  Alternatively, you can use the `|>` operator to invoke a given closure on each delegate.
     *
     *  - parameter invocation: The closure to be invoked on each delegate.
     */
    open func invokeDelegates(_ invocation: (T) -> ()) {
        
        for delegate in delegates.allObjects {
            invocation(delegate as! T)
        }
    }
    
    /**
     *  Use this method to determine if the multicast delegate contains a given delegate.
     *
     *  - parameter delegate:   The given delegate to check if it's contained
     *
     *  - returns: `true` if the delegate is found or `false` otherwise
     */
    open func containsDelegate(_ delegate: T) -> Bool {
        return delegates.contains((delegate as AnyObject))
    }
}
