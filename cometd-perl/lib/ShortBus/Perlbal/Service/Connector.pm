package Cometd::Perlbal::Service::Connector;


#    if ( $action eq 'stream' ) {
#        
#        my $send = sub {
#            shift;
#            my $body = shift;
#        
#            my $res = $self->{res_headers} = Perlbal::HTTPHeaders->new_response( 200 );
#            $res->header( "Content-Type", "text/html" );
#            $res->header( "Content-Length", length( $$body ) );
#            $self->setup_keepalive( $res );
#    
#            $self->state( "xfer_resp" );
#            $self->tcp_cork( 1 );
#            $self->write( $res->to_string_ref );
#        
#            unless ( $head->request_method eq "HEAD" ) {
#                $self->write( $body );
#            }
#        
#            $self->write( sub { $self->http_response_sent; } );
#        };
#
#        # create filehandle for reading
##        my $data = '';
##        Perlbal::AIO::aio_read($read_handle, 0, 2048, $data, sub {
#            # got data? undef is error
##            return $self->_simple_response(500) unless $_[0] > 0;
#
##            $ids{$id}->write( filter( { event => 'stream', data => $data } ) );
#            
#            # reenable writes after we get data
#            #$self->tcp_cork(1); # by setting reproxy_file_offset above, it won't cork, so we cork it
#            #$self->watch_write(1);
##        });
#        
#        # TODO inject
#        bcast_event( { data => "" } );
#
#        $send->( 'text/plain', \ 'OK' );
#        
#        return 1;
#    }
1;
