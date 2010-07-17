%% -------------------------------------------------------------------
%%
%% basho_bench: Benchmarking Suite
%%
%% Copyright (c) 2009-2010 Basho Techonologies
%%
%% This file is provided to you under the Apache License,
%% Version 2.0 (the "License"); you may not use this file
%% except in compliance with the License.  You may obtain
%% a copy of the License at
%%
%%   http://www.apache.org/licenses/LICENSE-2.0
%%
%% Unless required by applicable law or agreed to in writing,
%% software distributed under the License is distributed on an
%% "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
%% KIND, either express or implied.  See the License for the
%% specific language governing permissions and limitations
%% under the License.
%%
%% -------------------------------------------------------------------
-module(basho_bench_driver_riaksearch).

-export([new/1,
         run/4,
         valgen/2]).

-record(state, { nodes, fields, terms }).
-define(PRINT(Var), io:format("DEBUG: ~p:~p - ~p~n~n ~p~n~n", [?MODULE, ?LINE, ??Var, Var])).


%% ====================================================================
%% API
%% ====================================================================

new(_Id) ->
    MyNode = basho_bench_config:get(riaksearch_node),
    Cookie = basho_bench_config:get(riaksearch_cookie),
    net_kernel:start([MyNode]),
    erlang:set_cookie(MyNode, Cookie),

    %% Get the nodes, set the cookies...
    Nodes = basho_bench_config:get(riaksearch_remotenodes),

    %% Load the field array...
    FieldFile = basho_bench_config:get(riaksearch_fieldfile),
    FieldArray = file_to_array(FieldFile),
    
    %% Load the word array...
    TermFile = basho_bench_config:get(riaksearch_termfile),
    TermArray = file_to_array(TermFile),
    
    State = #state { 
        nodes=Nodes,
        fields=FieldArray, 
        terms=TermArray 
    },
    {ok, State}.


run('index', KeyGen, ValueGen, State) ->
    %% Make the index call...
    Node = choose(State#state.nodes),
    ID = KeyGen(),
    Fields = ValueGen(State#state.fields, State#state.terms),
    ok = rpc:call(Node, search, index_doc, [ID, Fields]),
    {ok, State};
run('query', _KeyGen, _ValueGen, _State) ->
    {error, not_yet_implemented}.


%% Given a file, split into newlines, and convert to an array.  Using
%% this because random access on an array is much faster than random
%% access on a list.
file_to_array(FilePath) ->
    case file:read_file(FilePath) of
        {ok, Bytes} ->
            List = binary_to_list(Bytes),
            Words = string:tokens(List, "\r\n"),
            array:from_list(Words);
        Error ->
            error_logger:error_msg("Could not read ~p.~n", [filename:absname(FilePath)]),
            throw({file_to_array, FilePath, Error})
    end.

%% This function is called by the basho_bench setup process. It
%% returns a valgen function that takes an array of Fields and an
%% array of Terms. The valgen function is then called by this module
%% (the driver).
valgen(MaxFields, MaxTerms) ->
    fun(Fields, Terms) ->
        %% Get the field names...
        NumFields = random:uniform(MaxFields),
        FieldNames = lists:usort([choose(Fields) || _ <- lists:seq(1, NumFields)]),

        %% Create the fields...
        [{X, construct_field(MaxTerms, Terms)} || X <- FieldNames]
    end.

%% @private
construct_field(MaxTerms, Terms) ->
    %% Get the list of terms...
    NumTerms = random:uniform(MaxTerms),
    L = [choose(Terms) || _ <- lists:seq(1, NumTerms)],
    string:join(L, " ").

%% Choose a random element from the List or Array.
choose(List) when is_list(List) ->
    N = random:uniform(length(List)),
    lists:nth(N, List);
choose(Array) when element(1, Array) == array ->
    N = random:uniform(Array:size()),
    Array:get(N - 1).
        
